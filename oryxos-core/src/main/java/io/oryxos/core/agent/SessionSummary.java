package io.oryxos.core.agent;

import java.time.Instant;
import java.util.Objects;

/** Row of the session list: identity, activity times and a short preview, never the transcript. */
public record SessionSummary(
    String sessionId,
    String profileName,
    String channel,
    String userId,
    int messageCount,
    String lastUserMessage,
    Instant createdAt,
    Instant updatedAt) {
  public SessionSummary {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(profileName, "profileName");
    Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }
}
