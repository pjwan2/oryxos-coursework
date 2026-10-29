package io.oryxos.core.agent;

import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.tool.ToolSpec;
import java.util.List;

/** Port: Provider implementation must only issue ONE LLM call and return a decision. */
public interface ModelGateway {
  ModelTurn chat(
      String sessionId, AgentProfile profile, List<AgentMessage> prompt, List<ToolSpec> tools);
}
