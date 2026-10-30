package io.oryxos.provider;

import io.oryxos.core.agent.ProfileNotFoundException;
import io.oryxos.core.profile.AgentDefinitionException;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.profile.ProfileCatalog;
import io.oryxos.core.profile.ProfileChangeListener;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The registry of live agents. Readers use an immutable snapshot that is swapped as a whole, so a
 * request never sees a half-updated map; writers are serialized. Every change is announced to the
 * {@link ProfileChangeListener}s (the sandbox drops its cached policy) before the call returns.
 */
public class ProfileRegistry implements ProfileCatalog {
  private volatile Map<String, AgentProfile> profiles;
  private final List<ProfileChangeListener> listeners = new CopyOnWriteArrayList<>();

  public ProfileRegistry(Map<String, AgentProfile> loaded) {
    profiles = Map.copyOf(loaded);
  }

  public void addListener(ProfileChangeListener listener) {
    listeners.add(Objects.requireNonNull(listener, "listener"));
  }

  @Override
  public AgentProfile get(String name) {
    AgentProfile profile = profiles.get(name);
    if (profile == null) {
      throw new ProfileNotFoundException(name);
    }
    return profile;
  }

  public boolean exists(String name) {
    return profiles.containsKey(name);
  }

  @Override
  public List<AgentProfile> list() {
    return profiles.values().stream().sorted(Comparator.comparing(AgentProfile::name)).toList();
  }

  public Map<String, AgentProfile> all() {
    return profiles;
  }

  /** Adds a new agent. An existing name is a conflict, never an overwrite. */
  public synchronized void register(AgentProfile profile) {
    Objects.requireNonNull(profile, "profile");
    if (profiles.containsKey(profile.name())) {
      throw new AgentDefinitionException(
          "Agent '" + profile.name() + "' is already registered; use replace to change it");
    }
    swap(profile.name(), null, profile);
  }

  /** Swaps the definition of a registered agent; returns the one it replaced. */
  public synchronized AgentProfile replace(AgentProfile profile) {
    Objects.requireNonNull(profile, "profile");
    AgentProfile before = profiles.get(profile.name());
    if (before == null) {
      throw new ProfileNotFoundException(profile.name());
    }
    swap(profile.name(), before, profile);
    return before;
  }

  /** Removes an agent; returns whether it was registered. */
  public synchronized boolean remove(String name) {
    AgentProfile before = profiles.get(name);
    if (before == null) {
      return false;
    }
    swap(name, before, null);
    return true;
  }

  private void swap(String name, AgentProfile before, AgentProfile after) {
    Map<String, AgentProfile> next = new LinkedHashMap<>(profiles);
    if (after == null) {
      next.remove(name);
    } else {
      next.put(name, after);
    }
    profiles = Map.copyOf(next);
    for (ProfileChangeListener listener : listeners) {
      listener.profileChanged(name, before, after);
    }
  }
}
