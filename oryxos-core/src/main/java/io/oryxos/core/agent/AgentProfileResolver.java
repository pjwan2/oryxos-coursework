package io.oryxos.core.agent;

import io.oryxos.core.profile.AgentProfile;

/** Port so the core engine does not depend on the provider module. */
@FunctionalInterface
public interface AgentProfileResolver {
  AgentProfile get(String name);
}
