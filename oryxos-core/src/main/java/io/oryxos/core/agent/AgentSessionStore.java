package io.oryxos.core.agent;

import java.util.Optional;

/** Persistence port. The core module knows nothing about JPA or SQLite. */
public interface AgentSessionStore {
  AgentSession loadOrCreate(String channel, String userId, String profileName);

  void save(AgentSession session);

  Optional<AgentSession> find(String sessionId);
}
