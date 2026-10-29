package io.oryxos.core.agent;

import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.tool.ToolSpec;
import java.util.List;

/** Port: enforces the Profile's tool allowlist before side effects. */
public interface AgentToolExecutor {
  List<ToolSpec> allowedSpecifications(AgentProfile profile);

  ToolOutput execute(String sessionId, AgentProfile profile, ToolAction action);
}
