package io.oryxos.web.api;

import io.oryxos.core.agent.SessionIds;

/** A session id {@code channel:userId:profile} taken apart again, each part already validated. */
record SessionRef(String channel, String userId, String profile) {
  static SessionRef parse(String sessionId) {
    String[] parts = sessionId == null ? new String[0] : sessionId.split(":", -1);
    if (parts.length != 3) {
      throw new InvalidRequestException("Invalid session id");
    }
    try {
      SessionIds.of(parts[0], parts[1], parts[2]);
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException("Invalid session id");
    }
    return new SessionRef(parts[0], parts[1], parts[2]);
  }

  String id() {
    return SessionIds.of(channel, userId, profile);
  }
}
