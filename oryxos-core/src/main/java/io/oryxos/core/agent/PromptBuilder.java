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
  private final List<ContextLoader> contextLoaders;

  public PromptBuilder(Clock clock, int maxHistoryMessages) {
    this(clock, maxHistoryMessages, List.of());
  }

  public PromptBuilder(Clock clock, int maxHistoryMessages, List<ContextLoader> contextLoaders) {
    this.clock = Objects.requireNonNull(clock, "clock");
    if (maxHistoryMessages < 1) {
      throw new IllegalArgumentException("maxHistoryMessages must be positive");
    }
    this.maxHistoryMessages = maxHistoryMessages;
    this.contextLoaders = List.copyOf(contextLoaders);
  }

  public List<AgentMessage> build(AgentSession session, AgentProfile profile) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(profile, "profile");
    StringBuilder system =
        new StringBuilder(
            String.valueOf(profile.identity().getOrDefault("prompt", "You are a helpful agent.")));
    system.append("\nCurrent date: ").append(LocalDate.now(clock));
    for (ContextLoader loader : contextLoaders) {
      String extra = loader.load(profile, session);
      if (extra != null && !extra.isBlank()) {
        system.append('\n').append(extra);
      }
    }
    List<AgentMessage> prompt = new ArrayList<>();
    prompt.add(new AgentMessage(AgentMessage.Role.SYSTEM, system.toString()));
    List<AgentMessage> previous = session.transcript();
    int from = Math.max(0, previous.size() - maxHistoryMessages);
    // A tool reply whose assistant tool-call message was cut off is rejected by chat APIs.
    while (from < previous.size() && previous.get(from).role() == AgentMessage.Role.TOOL) {
      from++;
    }
    prompt.addAll(previous.subList(from, previous.size()));
    return List.copyOf(prompt);
  }
}
