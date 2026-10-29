package io.oryxos.core.agent;

import java.time.Instant;
import java.util.Objects;

/** A single tool attempt, successful or not. Never stores tool arguments or secrets. */
public record ToolCallEvent(
    String sessionId,
    String toolName,
    boolean success,
    String errorCategory,
    long durationMs,
    Instant occurredAt) {
  public ToolCallEvent {
    Objects.requireNonNull(sessionId);
    Objects.requireNonNull(toolName);
    Objects.requireNonNull(occurredAt);
    if (durationMs < 0) {
      throw new IllegalArgumentException("negative duration");
    }
  }
}
