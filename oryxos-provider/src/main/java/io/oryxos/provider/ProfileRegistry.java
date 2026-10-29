package io.oryxos.provider;

import io.oryxos.core.agent.AgentProfileResolver;
import io.oryxos.core.profile.AgentProfile;
import java.util.Map;

/** Immutable baseline registry. Runtime registration is taught in lesson 29. */
public class ProfileRegistry implements AgentProfileResolver {
  private final Map<String, AgentProfile> profiles;

  public ProfileRegistry(Map<String, AgentProfile> loaded) {
    profiles = Map.copyOf(loaded);
  }

  public AgentProfile get(String name) {
    AgentProfile profile = profiles.get(name);
    if (profile == null) {
      throw new IllegalArgumentException("Unknown agent profile: " + name);
    }
    return profile;
  }

  public Map<String, AgentProfile> all() {
    return Map.copyOf(profiles);
  }
}
