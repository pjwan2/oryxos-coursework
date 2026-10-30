package io.oryxos.memory;

import io.oryxos.core.memory.AgentMemories;
import java.util.Objects;

/** The {@link MemoryService} an agent should use; the tools and the prompt loader ask per call. */
public interface MemoryServices {
  MemoryService forAgent(String agentName);

  /** One service for every agent (the pre-lesson-30 behaviour, and what the unit tests use). */
  static MemoryServices single(MemoryService service) {
    Objects.requireNonNull(service, "service");
    return agentName -> service;
  }

  /** A service over each agent's own store, with the same limits. */
  static MemoryServices perAgent(AgentMemories memories, MemoryService.Limits limits) {
    Objects.requireNonNull(memories, "memories");
    Objects.requireNonNull(limits, "limits");
    return agentName -> new MemoryService(memories.forAgent(agentName), limits);
  }
}
