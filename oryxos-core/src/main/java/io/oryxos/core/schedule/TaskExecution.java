package io.oryxos.core.schedule;

import java.time.Instant;
import java.util.Objects;

/** One finished run of a scheduled task. {@code errorMessage} is sanitized and null on success. */
public record TaskExecution(
    long id,
    String taskId,
    String sessionId,
    String trigger,
    Instant startedAt,
    boolean success,
    String errorMessage,
    long durationMs) {
  public static final String TRIGGER_CRON = "cron";
  public static final String TRIGGER_MANUAL = "manual";

  public TaskExecution {
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(trigger, "trigger");
    Objects.requireNonNull(startedAt, "startedAt");
  }
}
