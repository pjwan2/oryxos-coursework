package io.oryxos.core.memory;

/**
 * Which long-term memory belongs to which agent. An agent defined by an {@code AGENT.md} directory
 * has a private memory (its own {@code MEMORY.md}, or its own rows in SQLite). A legacy {@code
 * profiles/*.yaml} agent has no directory of its own and keeps using the shared global memory that
 * existed before. Nothing is ever copied from the global memory into a private one.
 */
public interface AgentMemories {
  /** The store this agent reads and writes. */
  LongTermMemoryStore forAgent(String agentName);

  /** True when the agent has a private memory, false when it uses the shared global one. */
  boolean isolated(String agentName);
}
