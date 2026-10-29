package io.oryxos.provider;

import java.time.Instant;

public record LlmCallEvent(
    String sessionId,
    String provider,
    String model,
    int promptTokens,
    int completionTokens,
    long durationMs,
    boolean success,
    String errorMessage,
    Instant occurredAt) {}
