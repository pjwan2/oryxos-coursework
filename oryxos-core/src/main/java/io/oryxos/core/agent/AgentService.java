package io.oryxos.core.agent;

import io.oryxos.core.profile.AgentProfile;
import java.util.Objects;

/** Orchestrates CLI / HTTP / scheduler through exactly one ReAct engine. */
public final class AgentService {
  private final ReActLoop loop;
  private final AgentProfileResolver profiles;
  private final AgentSessionStore sessions;
  private final SessionGates gates = new SessionGates();

  public AgentService(ReActLoop loop, AgentProfileResolver profiles, AgentSessionStore sessions) {
    this.loop = Objects.requireNonNull(loop);
    this.profiles = Objects.requireNonNull(profiles);
    this.sessions = Objects.requireNonNull(sessions);
  }

  public String process(String channel, String userId, String profileName, String message) {
    return process(channel, userId, profileName, message, null);
  }

  /**
   * Same, for a run that has an execution id (a scheduler run); the id is attached to the run's
   * tool audit rows. Messages of one session are handled one at a time.
   */
  public String process(
      String channel, String userId, String profileName, String message, String executionId) {
    // Validation must precede loading state or model calls.
    String id = SessionIds.of(channel, userId, profileName);
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("message is required");
    }
    AgentProfile profile = profiles.get(profileName);
    // Load, run and save inside the gate: a second message for this session waits here and then
    // sees the first one's answer in its history.
    try (SessionGates.Handle ignored = gates.enter(id)) {
      AgentSession session = sessions.loadOrCreate(channel, userId, profileName);
      if (!id.equals(session.id())) {
        throw new IllegalStateException("Session ID invariant violated");
      }
      ProfileContext.set(profile);
      try {
        return loop.run(session, message, profile, executionId);
      } finally {
        try {
          // Persist partial history even if a provider or a tool fails.
          sessions.save(session);
        } finally {
          ProfileContext.clear();
        }
      }
    }
  }
}
