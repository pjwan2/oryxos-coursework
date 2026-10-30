package io.oryxos.provider.agent;

import io.oryxos.core.profile.AgentProfile;
import io.oryxos.provider.ProfileLoader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The first load: the legacy {@code profiles/*.yaml} (read-only, unchanged) plus the {@code
 * agents/<name>/AGENT.md} directories. A name that both define is a deterministic conflict: the
 * legacy profile keeps running, the AGENT.md directory is not registered and is reported, and
 * nothing is ever overwritten silently.
 */
public final class AgentStartup {
  private static final Logger LOG = LoggerFactory.getLogger(AgentStartup.class);

  /**
   * @param profiles every agent to register, legacy and AGENT.md
   * @param agents the AGENT.md ones by name
   * @param legacy names that come from profiles/*.yaml
   * @param problems directory name to the reason it was not registered
   */
  public record Report(
      Map<String, AgentProfile> profiles,
      Map<String, AgentDefinition> agents,
      Set<String> legacy,
      Map<String, String> problems) {
    public Report {
      profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
      agents = Collections.unmodifiableMap(new LinkedHashMap<>(agents));
      legacy = Collections.unmodifiableSet(new TreeSet<>(legacy));
      problems = Collections.unmodifiableMap(new TreeMap<>(problems));
    }

    @Override
    public Map<String, AgentProfile> profiles() {
      return Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
    }

    @Override
    public Map<String, AgentDefinition> agents() {
      return Collections.unmodifiableMap(new LinkedHashMap<>(agents));
    }

    @Override
    public Set<String> legacy() {
      return Collections.unmodifiableSet(new TreeSet<>(legacy));
    }

    @Override
    public Map<String, String> problems() {
      return Collections.unmodifiableMap(new TreeMap<>(problems));
    }
  }

  private AgentStartup() {}

  public static Report load(ProfileLoader legacyLoader, AgentDirectoryScanner scanner) {
    Map<String, AgentProfile> legacy = legacyLoader.loadAll();
    Map<String, AgentProfile> profiles = new LinkedHashMap<>(legacy);
    Map<String, AgentDefinition> agents = new LinkedHashMap<>();
    AgentDirectoryScanner.Result found = scanner.scan();
    Map<String, String> problems = new TreeMap<>(found.problems());
    for (AgentDefinition agent : found.agents()) {
      if (legacy.containsKey(agent.name())) {
        String conflict = conflictMessage(agent.name());
        problems.put(agent.name(), conflict);
        LOG.error(conflict);
        continue;
      }
      profiles.put(agent.name(), agent.profile());
      agents.put(agent.name(), agent);
    }
    return new Report(profiles, agents, legacy.keySet(), problems);
  }

  /** The one wording used at startup and at runtime, so tests and operators see the same text. */
  public static String conflictMessage(String name) {
    return "Agent '"
        + name
        + "' is defined twice: by a legacy profiles/*.yaml and by agents/"
        + name
        + "/AGENT.md. Nothing was overridden: the legacy profile stays active and the AGENT.md"
        + " directory is not registered. Rename the agent or remove one of the two definitions.";
  }
}
