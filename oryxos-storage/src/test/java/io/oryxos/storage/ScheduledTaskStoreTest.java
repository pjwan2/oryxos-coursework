package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.schedule.ScheduledTask;
import io.oryxos.core.schedule.TaskExecution;
import io.oryxos.storage.migration.Migrations;
import io.oryxos.storage.migration.SqliteMigrator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

/** The store against a real, migrated SQLite file (the same schema the application uses). */
class ScheduledTaskStoreTest {
  private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

  @TempDir Path dir;

  private SQLiteDataSource dataSource;
  private JdbcScheduledTaskStore store;

  @BeforeEach
  void open() {
    dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + dir.resolve("tasks.db").toString().replace('\\', '/'));
    new SqliteMigrator(Migrations.ALL, Migrations.FRESH_SCHEMA_RESOURCE, true, Clock.systemUTC())
        .migrate(dataSource);
    store = new JdbcScheduledTaskStore(dataSource);
  }

  private ScheduledTask sync(String hash, String cron, String message) {
    return store.sync("weather.morning", "weather", cron, "Asia/Shanghai", message, hash);
  }

  private TaskExecution run(boolean success, String error, Instant at) {
    return new TaskExecution(
        0,
        "weather.morning",
        "scheduler:scheduler:weather",
        TaskExecution.TRIGGER_CRON,
        at,
        success,
        error,
        42);
  }

  @Test
  void aNewDefinitionIsStoredEnabledWithZeroRuns() {
    ScheduledTask task = sync("h1", "0 0 9 * * *", "Weather?");

    assertEquals("weather.morning", task.taskId());
    assertEquals("weather", task.profileName());
    assertEquals("Asia/Shanghai", task.zone());
    assertTrue(task.enabled());
    assertEquals(0, task.runCount());
    assertNull(task.lastStatus());
    assertNull(task.lastRunAt());
    assertNull(task.nextRunAt());
    assertEquals(List.of(task), store.findAll());
  }

  @Test
  void syncingTheSameDefinitionAgainKeepsEverythingTheDatabaseKnows() {
    sync("h1", "0 0 9 * * *", "Weather?");
    store.update("weather.morning", false, null, null, null, null);
    store.recordExecution(run(true, null, T0), T0.plusSeconds(60));

    ScheduledTask again = sync("h1", "0 0 9 * * *", "Weather?");

    assertFalse(again.enabled(), "a disabled task must stay disabled across restarts");
    assertEquals(1, again.runCount());
    assertEquals("SUCCESS", again.lastStatus());
    assertEquals(T0, again.lastRunAt());
    assertEquals(1, store.executions("weather.morning", 10).size());
  }

  @Test
  void aRuntimeEditSurvivesARestartUnlessTheProfileFileChanged() {
    sync("h1", "0 0 9 * * *", "Weather?");
    store.update("weather.morning", null, "0 30 18 * * *", null, "Edited", null);

    ScheduledTask restart = sync("h1", "0 0 9 * * *", "Weather?");
    assertEquals("0 30 18 * * *", restart.cron(), "profile unchanged: the edit stays");
    assertEquals("Edited", restart.message());

    store.update("weather.morning", false, null, null, null, null);
    ScheduledTask changedFile = sync("h2", "0 0 7 * * *", "New text");
    assertEquals("0 0 7 * * *", changedFile.cron(), "profile changed: the file wins");
    assertEquals("New text", changedFile.message());
    assertFalse(changedFile.enabled(), "but the enabled switch is never reset by a definition");
  }

  @Test
  void recordingAnExecutionStoresTheRowAndUpdatesCountersAtomically() {
    sync("h1", "0 0 9 * * *", "m");

    TaskExecution saved = store.recordExecution(run(true, null, T0), T0.plusSeconds(86400));
    store.recordExecution(
        run(false, "Agent run failed (IllegalStateException)", T0.plusSeconds(5)), null);

    assertTrue(saved.id() > 0);
    ScheduledTask task = store.find("weather.morning").orElseThrow();
    assertEquals(2, task.runCount());
    assertEquals("FAILED", task.lastStatus());
    assertEquals(T0.plusSeconds(5), task.lastRunAt());
    assertNull(task.nextRunAt());

    List<TaskExecution> history = store.executions("weather.morning", 10);
    assertEquals(2, history.size());
    assertFalse(history.get(0).success(), "newest first");
    assertEquals("Agent run failed (IllegalStateException)", history.get(0).errorMessage());
    assertTrue(history.get(1).success());
    assertNull(history.get(1).errorMessage());
    assertEquals("scheduler:scheduler:weather", history.get(1).sessionId());
    assertEquals(42, history.get(1).durationMs());
    assertEquals("cron", history.get(1).trigger());
  }

  @Test
  void aFailureInsideRecordingRollsBackBothTheRowAndTheCounters() {
    sync("h1", "0 0 9 * * *", "m");
    // Drive the same @Transactional boundary by hand: run both statements in one transaction and
    // fail.
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    assertThrows(
        IllegalStateException.class,
        () ->
            tx.executeWithoutResult(
                status -> {
                  store.recordExecution(run(true, null, T0), T0);
                  throw new IllegalStateException("boom");
                }));
    assertEquals(0, store.executions("weather.morning", 10).size());
    assertEquals(0, store.find("weather.morning").orElseThrow().runCount());
  }

  @Test
  void executionsRespectTheLimitAndAreScopedPerTask() {
    sync("h1", "0 0 9 * * *", "m");
    store.sync("other.task", "other", "0 0 9 * * *", "UTC", "m", "x");
    for (int i = 0; i < 5; i++) {
      store.recordExecution(run(true, null, T0.plusSeconds(i)), null);
    }
    store.recordExecution(
        new TaskExecution(
            0, "other.task", "scheduler:scheduler:other", "manual", T0, true, null, 1),
        null);

    assertEquals(3, store.executions("weather.morning", 3).size());
    assertEquals(1, store.executions("other.task", 10).size());
    assertEquals(0, store.executions("missing", 10).size());
  }

  @Test
  void updatingAnUnknownTaskIsAnError() {
    assertThrows(
        IllegalArgumentException.class, () -> store.update("nope", true, null, null, null, null));
    assertTrue(store.find("nope").isEmpty());
  }

  @Test
  void dataSurvivesClosingAndReopeningTheDatabase() {
    sync("h1", "0 0 9 * * *", "m");
    store.recordExecution(run(true, null, T0), T0.plusSeconds(60));
    store.update("weather.morning", false, null, null, null, null);

    JdbcScheduledTaskStore reopened = new JdbcScheduledTaskStore(dataSource);
    new SqliteMigrator(Migrations.ALL, Migrations.FRESH_SCHEMA_RESOURCE, true, Clock.systemUTC())
        .migrate(dataSource);
    ScheduledTask task = reopened.find("weather.morning").orElseThrow();
    assertFalse(task.enabled());
    assertEquals(1, task.runCount());
    assertEquals(1, reopened.executions("weather.morning", 10).size());
  }

  @Test
  void theEnabledColumnRejectsValuesOtherThanZeroAndOne() {
    sync("h1", "0 0 9 * * *", "m");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThrows(
        RuntimeException.class,
        () ->
            jdbc.update(
                "UPDATE scheduled_tasks SET enabled = 7 WHERE task_id = 'weather.morning'"));
  }
}
