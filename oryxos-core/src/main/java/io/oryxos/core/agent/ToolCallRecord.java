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
    Instant occurredAt) {}
