package io.oryxos.core.agent;

import io.oryxos.core.profile.AgentProfile;

/** Supplies extra system-prompt context (e.g. memory) for each model call; never null. */
@FunctionalInterface
public interface ContextLoader {
  String load(AgentProfile profile, AgentSession session);
}
