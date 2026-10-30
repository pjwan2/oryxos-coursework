package io.oryxos.core.agent;

import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.tool.ToolSpec;
import java.util.List;

/** Port: enforces the Profile's tool allowlist before side effects. */
public interface AgentToolExecutor {
  List<ToolSpec> allowedSpecifications(AgentProfile profile);

  ToolOutput execute(String sessionId, AgentProfile profile, ToolAction action);

  /**
   * Same, for a run that has an execution id (a scheduler run). The id ties the audit rows of this
   * run together; implementations that do not audit simply ignore it.
   */
  default ToolOutput execute(
      String sessionId, String executionId, AgentProfile profile, ToolAction action) {
    return execute(sessionId, profile, action);
  }
}
