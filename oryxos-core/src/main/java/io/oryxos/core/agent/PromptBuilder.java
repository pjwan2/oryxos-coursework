package io.oryxos.core.agent;

import io.oryxos.core.profile.AgentProfile;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Builds a bounded prompt, keeping system instructions out of the mutable session history. */
public final class PromptBuilder {
  private final Clock clock;
  private final int maxHistoryMessages;

  public PromptBuilder(Clock clock, int maxHistoryMessages) {
    this.clock = Objects.requireNonNull(clock, "clock");
    if (maxHistoryMessages < 1) {
      throw new IllegalArgumentException("maxHistoryMessages must be positive");
    }
    this.maxHistoryMessages = maxHistoryMessages;
  }

  public List<AgentMessage> build(AgentSession session, AgentProfile profile) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(profile, "profile");
    String identity =
        String.valueOf(profile.identity().getOrDefault("prompt", "You are a helpful agent."));
    String system = identity + "\nCurrent date: " + LocalDate.now(clock);
    List<AgentMessage> prompt = new ArrayList<>();
    prompt.add(new AgentMessage(AgentMessage.Role.SYSTEM, system));
    List<AgentMessage> previous = session.transcript();
    int from = Math.max(0, previous.size() - maxHistoryMessages);
    prompt.addAll(previous.subList(from, previous.size()));
    return List.copyOf(prompt);
  }
}
