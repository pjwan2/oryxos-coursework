package io.oryxos.provider.agent;

import io.oryxos.core.profile.AgentConflictException;
import io.oryxos.core.profile.AgentDefinitionException;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.schedule.ProfileScheduleSync;
import io.oryxos.provider.ProfileRegistry;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one way an agent directory becomes a live agent, whatever brought it here: the startup scan,
 * a copied directory noticed by the watcher, or the REST API. {@link #register} always runs the
 * same steps: read and validate the directory, check the name is free (or already ours), swap the
 * registry, tell the scheduler; a failure at any step puts the previous state back, so there is no
 * half-registered agent.
 */
public class AgentLifecycleService {
  private static final Logger LOG = LoggerFactory.getLogger(AgentLifecycleService.class);

  private final ProfileRegistry registry;
  private final AgentDirectoryScanner scanner;
  private final AgentLoader loader;
  private final ProfileScheduleSync scheduler;
  private final Map<String, AgentDefinition> agents = new ConcurrentHashMap<>();
  private final Map<String, String> problems = new ConcurrentHashMap<>();

  public AgentLifecycleService(
      ProfileRegistry registry,
      AgentDirectoryScanner scanner,
      AgentLoader loader,
      ProfileScheduleSync scheduler,
      AgentStartup.Report startup) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.scanner = Objects.requireNonNull(scanner, "scanner");
    this.loader = Objects.requireNonNull(loader, "loader");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    agents.putAll(startup.agents());
    problems.putAll(startup.problems());
  }

  public Path root() {
    return scanner.root();
  }

  public AgentLoader loader() {
    return loader;
  }

  /** Directory of the named agent under the agents root (not checked for existence). */
  public Path directoryOf(String name) {
    return scanner.root().resolve(name);
  }

  /**
   * Registers the agent in {@code agentDir}, or reloads it when it is already ours. An existing
   * valid agent stays untouched if the new files are invalid.
   */
  public synchronized AgentDefinition register(Path agentDir) {
    String name = AgentLoader.nameOf(agentDir);
    AgentDefinition definition;
    try {
      scanner.requireInside(agentDir);
      definition = loader.load(agentDir);
    } catch (AgentDefinitionException invalid) {
      problems.put(name, invalid.getMessage());
      throw invalid;
    }
    AgentProfile before = registry.exists(name) ? registry.get(name) : null;
    if (before != null && !agents.containsKey(name)) {
      String conflict = AgentStartup.conflictMessage(name);
      problems.put(name, conflict);
      throw new AgentConflictException(conflict);
    }
    apply(definition, before);
    agents.put(name, definition);
    problems.remove(name);
    LOG.info("Agent '{}' {}", name, before == null ? "registered" : "reloaded");
    return definition;
  }

  /** Re-reads the directory of an agent that is already registered from AGENT.md. */
  public synchronized AgentDefinition reload(String name) {
    if (!agents.containsKey(name)) {
      if (registry.exists(name)) {
        throw new AgentConflictException(
            "Agent '" + name + "' comes from a legacy profiles/*.yaml and is read-only");
      }
      throw new io.oryxos.core.agent.ProfileNotFoundException(name);
    }
    return register(directoryOf(name));
  }

  /** Stops and forgets an AGENT.md agent; false if there was none. Files are not touched. */
  public synchronized boolean unregister(String name) {
    if (!agents.containsKey(name)) {
      if (registry.exists(name)) {
        throw new AgentConflictException(
            "Agent '" + name + "' comes from a legacy profiles/*.yaml and is read-only");
      }
      return false;
    }
    AgentProfile before = registry.exists(name) ? registry.get(name) : null;
    registry.remove(name);
    try {
      scheduler.unregisterProfile(name);
    } catch (RuntimeException failure) {
      if (before != null) {
        registry.register(before);
      }
      throw failure;
    }
    agents.remove(name);
    problems.remove(name);
    LOG.info("Agent '{}' unregistered", name);
    return true;
  }

  /** Validates a directory exactly as register would, without registering anything. */
  public AgentDefinition validate(Path agentDir) {
    scanner.requireInside(agentDir);
    return loader.load(agentDir);
  }

  public Optional<AgentDefinition> definition(String name) {
    return Optional.ofNullable(agents.get(name));
  }

  /** True when the agent is registered but has no AGENT.md (a legacy, read-only profile). */
  public boolean isLegacy(String name) {
    return registry.exists(name) && !agents.containsKey(name);
  }

  /** Directory name to the reason it is not (or no longer cleanly) registered. */
  public Map<String, String> problems() {
    return Collections.unmodifiableMap(new TreeMap<>(problems));
  }

  public void recordProblem(String name, String message) {
    problems.put(name, message);
  }

  public void clearProblem(String name) {
    problems.remove(name);
  }

  private void apply(AgentDefinition definition, AgentProfile before) {
    AgentProfile profile = definition.profile();
    try {
      if (before == null) {
        registry.register(profile);
      } else {
        registry.replace(profile);
      }
      scheduler.registerProfile(profile);
    } catch (RuntimeException failure) {
      restore(profile.name(), before);
      throw failure;
    }
  }

  private void restore(String name, AgentProfile before) {
    try {
      if (before == null) {
        registry.remove(name);
        scheduler.unregisterProfile(name);
      } else {
        registry.replace(before);
        scheduler.registerProfile(before);
      }
    } catch (RuntimeException secondary) {
      LOG.error("Could not restore agent '{}' after a failure: {}", name, secondary.toString());
    }
  }
}
