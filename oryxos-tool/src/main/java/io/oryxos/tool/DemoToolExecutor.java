package io.oryxos.tool;

import io.oryxos.core.agent.AgentToolExecutor;
import io.oryxos.core.agent.ToolAction;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.tool.ToolContext;
import io.oryxos.core.tool.ToolSpec;
import java.util.List;
import java.util.Objects;

/**
 * Read-only teaching executor kept for the offline lesson. Real wiring uses RegistryToolExecutor;
 * this delegates to the same GetPaymentTool so the sample data lives in one place.
 */
public final class DemoToolExecutor implements AgentToolExecutor {
  private static final GetPaymentTool LOOKUP = new GetPaymentTool();

  @Override
  public List<ToolSpec> allowedSpecifications(AgentProfile profile) {
    Objects.requireNonNull(profile, "profile");
    return profile.tools().contains(LOOKUP.getName()) ? List.of(LOOKUP.toSpec()) : List.of();
  }

  @Override
  public ToolOutput execute(String sessionId, AgentProfile profile, ToolAction action) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(action, "action");
    if (!profile.tools().contains(action.name()) || !LOOKUP.getName().equals(action.name())) {
      throw new SecurityException("Tool not permitted: " + action.name());
    }
    return LOOKUP.execute(action.argumentsJson(), new ToolContext(sessionId, profile));
  }
}
