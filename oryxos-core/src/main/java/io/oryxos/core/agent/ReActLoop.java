package io.oryxos.core.agent;

import io.oryxos.core.profile.AgentProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Small, testable Reason -> Act -> Observe orchestrator, with hard stop condition. */
public final class ReActLoop {
  public static final int DEFAULT_MAX_ITERATIONS = 10;
  private final ModelGateway provider;
  private final AgentToolExecutor tools;
  private final PromptBuilder prompts;

  public ReActLoop(ModelGateway provider, AgentToolExecutor tools, PromptBuilder prompts) {
    this.provider = Objects.requireNonNull(provider, "provider");
    this.tools = Objects.requireNonNull(tools, "tools");
    this.prompts = Objects.requireNonNull(prompts, "prompts");
  }

  public String run(AgentSession session, String message, AgentProfile profile) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(message, "message");
    if (message.isBlank()) {
      throw new IllegalArgumentException("message is blank");
    }
    if (!session.profileName().equals(profile.name())) {
      throw new IllegalArgumentException("Session/Profile mismatch");
    }
    session.append(new AgentMessage(AgentMessage.Role.USER, message));
    int limit = iterations(profile);
    for (int step = 0; step < limit; step++) {
      ModelTurn decision =
          Objects.requireNonNull(
              provider.chat(
                  session.id(),
                  profile,
                  prompts.build(session, profile),
                  tools.allowedSpecifications(profile)));
      if (decision.actions().isEmpty()) {
        session.append(new AgentMessage(AgentMessage.Role.ASSISTANT, decision.text()));
        return decision.text();
      }
      // One native assistant message can request several tools; keep all
      // calls together, then persist one matching tool-response message.
      session.append(AgentMessage.requests(decision.actions()));
      List<AgentMessage.ToolReply> replies = new ArrayList<>();
      for (ToolAction action : decision.actions()) {
        ToolOutput output;
        try {
          output = tools.execute(session.id(), profile, action);
        } catch (RuntimeException failure) {
          // Preserve partial evidence; never call a model again after an
          // incomplete batch or an authorization exception.
          replies.add(
              new AgentMessage.ToolReply(
                  action.id(), action.name(), "ERROR " + failure.getClass().getSimpleName()));
          session.append(AgentMessage.replies(replies));
          throw failure;
        }
        String observation = output.success() ? output.content() : "ERROR " + output.errorMessage();
        replies.add(new AgentMessage.ToolReply(action.id(), action.name(), observation));
      }
      session.append(AgentMessage.replies(replies));
    }
    String exhausted = "达到最大轮数，已停止";
    session.append(new AgentMessage(AgentMessage.Role.ASSISTANT, exhausted));
    return exhausted;
  }

  private static int iterations(AgentProfile profile) {
    Object raw = profile.settings().getOrDefault("max_iterations", DEFAULT_MAX_ITERATIONS);
    int result;
    try {
      result = Integer.parseInt(String.valueOf(raw));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("max_iterations must be an integer", exception);
    }
    if (result < 1 || result > 100) {
      throw new IllegalArgumentException("max_iterations must be 1..100");
    }
    return result;
  }
}
