package io.oryxos.core.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Mutable session with defensive transcript snapshots, not yet persisted (lesson 18). */
public final class AgentSession {
  private final String id;
  private final String profileName;
  private final List<AgentMessage> messages = new ArrayList<>();

  public AgentSession(String id, String profileName) {
    this.id = Objects.requireNonNull(id, "id");
    this.profileName = Objects.requireNonNull(profileName, "profileName");
    if (id.isBlank() || profileName.isBlank()) {
      throw new IllegalArgumentException("id and profileName cannot be blank");
    }
  }

  public static AgentSession restore(String id, String profileName, List<AgentMessage> history) {
    AgentSession session = new AgentSession(id, profileName);
    for (AgentMessage step : List.copyOf(Objects.requireNonNull(history, "history"))) {
      session.append(step);
    }
    return session;
  }

  public String id() {
    return id;
  }

  public String profileName() {
    return profileName;
  }

  public void append(AgentMessage message) {
    messages.add(Objects.requireNonNull(message));
  }

  public List<AgentMessage> transcript() {
    return List.copyOf(messages);
  }
}
