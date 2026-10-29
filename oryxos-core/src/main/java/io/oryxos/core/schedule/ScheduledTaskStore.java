package io.oryxos.core.schedule;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port for schedules and their run history. The core knows nothing about SQL. */
public interface ScheduledTaskStore {
  /**
   * Registers a definition coming from a Profile. A new task is inserted enabled. For a known task
   * the stored cron/zone/message are replaced only when {@code definitionHash} differs from the one
   * stored (the Profile file changed); otherwise runtime edits made through the API are kept.
   * Enabled state, counters, status and history are never reset.
   */
  ScheduledTask sync(
      String taskId,
      String profileName,
      String cron,
      String zone,
      String message,
      String definitionHash);

  Optional<ScheduledTask> find(String taskId);

  List<ScheduledTask> findAll();

  /** Applies a runtime edit; null arguments leave the field unchanged. */
  ScheduledTask update(
      String taskId, Boolean enabled, String cron, String zone, String message, Instant nextRunAt);

  void updateNextRun(String taskId, Instant nextRunAt);

  /** Atomically stores the execution row and updates counters, status and the next run time. */
  TaskExecution recordExecution(TaskExecution execution, Instant nextRunAt);

  /** Newest first. */
  List<TaskExecution> executions(String taskId, int limit);
}
