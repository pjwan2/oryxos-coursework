package io.oryxos.core.schedule;

import java.time.Instant;
import java.util.Objects;

/**
 * One persisted schedule. {@code taskId} is {@code <profile>.<localId>} so two agents can both own
 * a local id such as "daily" without clashing. Counters and status survive restarts.
 */
public record ScheduledTask(
    String taskId,
    String profileName,
    String cron,
    String zone,
    String message,
    boolean enabled,
    Instant nextRunAt,
    Instant lastRunAt,
    String lastStatus,
    long runCount) {
  public static final String STATUS_SUCCESS = "SUCCESS";
  public static final String STATUS_FAILED = "FAILED";

  public ScheduledTask {
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(profileName, "profileName");
    Objects.requireNonNull(cron, "cron");
    Objects.requireNonNull(zone, "zone");
    Objects.requireNonNull(message, "message");
  }
}
