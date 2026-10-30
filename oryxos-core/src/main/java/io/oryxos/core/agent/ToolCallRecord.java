package io.oryxos.core.agent;

import java.time.Instant;

/** A stored tool audit row as the admin API shows it (arguments are never stored). */
public record ToolCallRecord(
    long id,
    String sessionId,
    String toolName,
    boolean success,
    String errorCategory,
    String errorMessage,
    long durationMs,
    Instant occurredAt,
    String executionId) {
  /** A row that carries no execution id (a chat message, or a row from before version 4). */
  public ToolCallRecord(
      long id,
      String sessionId,
      String toolName,
      boolean success,
      String errorCategory,
      String errorMessage,
      long durationMs,
      Instant occurredAt) {
    this(
        id,
        sessionId,
        toolName,
        success,
        errorCategory,
        errorMessage,
        durationMs,
        occurredAt,
        null);
  }
}
