package io.oryxos.provider;

import io.oryxos.core.agent.ProfileNotFoundException;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.profile.ProfileCatalog;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Immutable baseline registry. Runtime registration is taught in lesson 29. */
public class ProfileRegistry implements ProfileCatalog {
  private final Map<String, AgentProfile> profiles;

  public ProfileRegistry(Map<String, AgentProfile> loaded) {
    profiles = Map.copyOf(loaded);
  }

  @Override
  public AgentProfile get(String name) {
    AgentProfile profile = profiles.get(name);
    if (profile == null) {
      throw new ProfileNotFoundException(name);
    }
    return profile;
  }

  @Override
  public List<AgentProfile> list() {
    return profiles.values().stream().sorted(Comparator.comparing(AgentProfile::name)).toList();
  }

  public Map<String, AgentProfile> all() {
    return Map.copyOf(profiles);
  }
}
