package io.oryxos.storage;

import io.oryxos.core.schedule.ScheduledTask;
import io.oryxos.core.schedule.ScheduledTaskStore;
import io.oryxos.core.schedule.TaskExecution;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Schedules and run history in SQLite. Plain JDBC on the migrated schema (tables are owned by
 * SqliteMigrator, not by Hibernate). Times are stored as ISO-8601 UTC text.
 */
@Repository("jdbcScheduledTaskStore")
public class JdbcScheduledTaskStore implements ScheduledTaskStore {
  private static final String TASK_COLUMNS =
      "task_id, profile_name, cron, zone, message, enabled, next_run_at, last_run_at, "
          + "last_status, run_count";

  private static final RowMapper<ScheduledTask> TASK =
      (rs, row) ->
          new ScheduledTask(
              rs.getString("task_id"),
              rs.getString("profile_name"),
              rs.getString("cron"),
              rs.getString("zone"),
              rs.getString("message"),
              rs.getInt("enabled") == 1,
              instant(rs, "next_run_at"),
              instant(rs, "last_run_at"),
              rs.getString("last_status"),
              rs.getLong("run_count"));

  private static final RowMapper<TaskExecution> EXECUTION =
      (rs, row) ->
          new TaskExecution(
              rs.getLong("id"),
              rs.getString("task_id"),
              rs.getString("session_id"),
              rs.getString("trigger_type"),
              Instant.parse(rs.getString("started_at")),
              rs.getInt("success") == 1,
              rs.getString("error_message"),
              rs.getLong("duration_ms"),
              rs.getString("execution_id"));

  private final JdbcTemplate jdbc;

  public JdbcScheduledTaskStore(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    String value = rs.getString(column);
    return value == null ? null : Instant.parse(value);
  }

  private static String text(Instant value) {
    return value == null ? null : value.toString();
  }

  @Override
  @Transactional
  public ScheduledTask sync(
      String taskId,
      String profileName,
      String cron,
      String zone,
      String message,
      String definitionHash) {
    Optional<String> storedHash =
        jdbc
            .query(
                "SELECT definition_hash FROM scheduled_tasks WHERE task_id = ?",
                (rs, row) -> rs.getString(1),
                taskId)
            .stream()
            .findFirst();
    if (storedHash.isEmpty()) {
      jdbc.update(
          "INSERT INTO scheduled_tasks(task_id, profile_name, cron, zone, message, enabled, "
              + "run_count, definition_hash) VALUES (?, ?, ?, ?, ?, 1, 0, ?)",
          taskId,
          profileName,
          cron,
          zone,
          message,
          definitionHash);
    } else if (!storedHash.get().equals(definitionHash)) {
      jdbc.update(
          "UPDATE scheduled_tasks SET profile_name = ?, cron = ?, zone = ?, message = ?, "
              + "definition_hash = ? WHERE task_id = ?",
          profileName,
          cron,
          zone,
          message,
          definitionHash,
          taskId);
    }
    return find(taskId).orElseThrow();
  }

  @Override
  public Optional<ScheduledTask> find(String taskId) {
    return jdbc
        .query("SELECT " + TASK_COLUMNS + " FROM scheduled_tasks WHERE task_id = ?", TASK, taskId)
        .stream()
        .findFirst();
  }

  @Override
  public List<ScheduledTask> findAll() {
    return jdbc.query("SELECT " + TASK_COLUMNS + " FROM scheduled_tasks ORDER BY task_id", TASK);
  }

  @Override
  @Transactional
  public ScheduledTask update(
      String taskId, Boolean enabled, String cron, String zone, String message, Instant nextRunAt) {
    int changed =
        jdbc.update(
            "UPDATE scheduled_tasks SET enabled = COALESCE(?, enabled), cron = COALESCE(?, cron), "
                + "zone = COALESCE(?, zone), message = COALESCE(?, message), next_run_at = ? "
                + "WHERE task_id = ?",
            enabled == null ? null : Integer.valueOf(enabled ? 1 : 0),
            cron,
            zone,
            message,
            text(nextRunAt),
            taskId);
    if (changed == 0) {
      throw new IllegalArgumentException("Unknown schedule");
    }
    return find(taskId).orElseThrow();
  }

  @Override
  public void updateNextRun(String taskId, Instant nextRunAt) {
    jdbc.update(
        "UPDATE scheduled_tasks SET next_run_at = ? WHERE task_id = ?", text(nextRunAt), taskId);
  }

  @Override
  @Transactional
  public TaskExecution recordExecution(TaskExecution execution, Instant nextRunAt) {
    Long id =
        jdbc.queryForObject(
            "INSERT INTO task_executions(task_id, session_id, trigger_type, started_at, success, "
                + "error_message, duration_ms, execution_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                + "RETURNING id",
            Long.class,
            execution.taskId(),
            execution.sessionId(),
            execution.trigger(),
            execution.startedAt().toString(),
            execution.success() ? 1 : 0,
            execution.errorMessage(),
            execution.durationMs(),
            execution.executionId());
    jdbc.update(
        "UPDATE scheduled_tasks SET run_count = run_count + 1, last_run_at = ?, last_status = ?, "
            + "next_run_at = ? WHERE task_id = ?",
        execution.startedAt().toString(),
        execution.success() ? ScheduledTask.STATUS_SUCCESS : ScheduledTask.STATUS_FAILED,
        text(nextRunAt),
        execution.taskId());
    return new TaskExecution(
        id == null ? 0 : id,
        execution.taskId(),
        execution.sessionId(),
        execution.trigger(),
        execution.startedAt(),
        execution.success(),
        execution.errorMessage(),
        execution.durationMs(),
        execution.executionId());
  }

  @Override
  public List<TaskExecution> executions(String taskId, int limit) {
    return jdbc.query(
        "SELECT id, task_id, session_id, trigger_type, started_at, success, error_message, "
            + "duration_ms, execution_id FROM task_executions WHERE task_id = ? ORDER BY id DESC LIMIT ?",
        EXECUTION,
        taskId,
        Math.max(1, limit));
  }
}
