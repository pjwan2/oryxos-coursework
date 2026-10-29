package io.oryxos.core.agent;

import java.time.Instant;
import java.util.Objects;

/**
 * A single tool attempt, successful or not. Never stores tool arguments or secrets; {@code
 * errorMessage} is sanitized on construction (see {@link SafeErrorText}) and null on success.
 */
public record ToolCallEvent(
    String sessionId,
    String toolName,
    boolean success,
    String errorCategory,
    String errorMessage,
    long durationMs,
    Instant occurredAt) {
  public ToolCallEvent {
    Objects.requireNonNull(sessionId);
    Objects.requireNonNull(toolName);
    Objects.requireNonNull(occurredAt);
    if (durationMs < 0) {
      throw new IllegalArgumentException("negative duration");
    }
    errorMessage = SafeErrorText.sanitize(errorMessage);
  }

  /** Event without a readable reason, as recorded before the audit table had a message column. */
  public ToolCallEvent(
      String sessionId,
      String toolName,
      boolean success,
      String errorCategory,
      long durationMs,
      Instant occurredAt) {
    this(sessionId, toolName, success, errorCategory, null, durationMs, occurredAt);
  }
}
