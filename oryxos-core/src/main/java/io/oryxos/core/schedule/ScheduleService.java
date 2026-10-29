package io.oryxos.core.schedule;

import java.util.List;

/** What the admin API may do with schedules. The implementation lives in oryxos-scheduler. */
public interface ScheduleService {
  List<ScheduleView> list();

  /** Newest first. */
  List<TaskExecution> executions(String taskId, int limit);

  /**
   * Runs the task now on the calling thread through the normal agent path. Subject to the same
   * no-overlap rule as cron triggers.
   */
  TaskExecution runNow(String taskId);

  ScheduleView update(String taskId, ScheduleUpdate update);
}
