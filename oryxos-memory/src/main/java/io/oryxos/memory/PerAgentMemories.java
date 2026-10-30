package io.oryxos.memory;

import io.oryxos.core.memory.AgentMemories;
import io.oryxos.core.memory.LongTermMemoryStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Routes memory by agent name. {@code homeOf} says where an AGENT.md agent lives (empty for a
 * legacy profile, which keeps the shared global store). The per-agent store is chosen by the
 * backend through a {@link Factory}; a backend that cannot isolate refuses with a clear error.
 * Stores are kept per agent so concurrent writers share one monitor and cannot lose each other's
 * entries.
 */
public final class PerAgentMemories implements AgentMemories {
  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  /** Builds the private store of one agent. */
  @FunctionalInterface
  public interface Factory {
    LongTermMemoryStore create(String agentName, Path agentHome);
  }

  private record Cached(Path home, LongTermMemoryStore store) {}

  private final LongTermMemoryStore global;
  private final Function<String, Optional<Path>> homeOf;
  private final Factory factory;
  private final Map<String, Cached> stores = new ConcurrentHashMap<>();

  public PerAgentMemories(
      LongTermMemoryStore global, Function<String, Optional<Path>> homeOf, Factory factory) {
    this.global = Objects.requireNonNull(global, "global");
    this.homeOf = Objects.requireNonNull(homeOf, "homeOf");
    this.factory = Objects.requireNonNull(factory, "factory");
  }

  @Override
  public LongTermMemoryStore forAgent(String agentName) {
    Optional<Path> home = homeFor(agentName);
    if (home.isEmpty()) {
      return global;
    }
    Cached cached = stores.get(agentName);
    if (cached != null && cached.home().equals(home.get())) {
      return cached.store();
    }
    LongTermMemoryStore created = factory.create(agentName, home.get());
    stores.put(agentName, new Cached(home.get(), created));
    return created;
  }

  @Override
  public boolean isolated(String agentName) {
    return homeFor(agentName).isPresent();
  }

  private Optional<Path> homeFor(String agentName) {
    if (agentName == null || !SAFE_NAME.matcher(agentName).matches()) {
      throw new IllegalArgumentException("Invalid agent name");
    }
    return homeOf.apply(agentName);
  }
}
