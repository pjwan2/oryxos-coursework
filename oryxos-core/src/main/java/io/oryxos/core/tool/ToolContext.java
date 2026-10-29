package io.oryxos.core.tool;

import io.oryxos.core.profile.AgentProfile;
import java.util.Objects;

/** Who is calling: the session and the already-authorised Profile. */
public record ToolContext(String sessionId, AgentProfile profile) {
  public ToolContext {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(profile, "profile");
  }
}
