package io.oryxos.core.schedule;

import java.time.Instant;

/** What the admin API shows for a task: the stored state plus whether a run is in flight now. */
public record ScheduleView(
    String taskId,
    String profileName,
    String cron,
    String zone,
    String message,
    boolean enabled,
    boolean running,
    Instant nextRunAt,
    Instant lastRunAt,
    String lastStatus,
    long runCount) {
  public static ScheduleView of(ScheduledTask task, boolean running) {
    return new ScheduleView(
        task.taskId(),
        task.profileName(),
        task.cron(),
        task.zone(),
        task.message(),
        task.enabled(),
        running,
        task.nextRunAt(),
        task.lastRunAt(),
        task.lastStatus(),
        task.runCount());
  }
}
