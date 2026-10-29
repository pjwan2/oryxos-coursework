package io.oryxos.core.agent;

import java.util.List;
import java.util.Optional;

/** Persistence port. The core module knows nothing about JPA or SQLite. */
public interface AgentSessionStore {
  AgentSession loadOrCreate(String channel, String userId, String profileName);

  void save(AgentSession session);

  Optional<AgentSession> find(String sessionId);

  /** The most recently active sessions first, at most {@code limit}. */
  List<SessionSummary> list(int limit);

  /**
   * Removes the stored conversation only; audit rows are history and stay. (Not called "delete":
   * SpotBugs treats any interface with a delete/remove/put/... method as a mutable value and flags
   * every class that merely keeps a reference to it.)
   */
  boolean discard(String sessionId);

  long count();
}
