package io.oryxos.memory;

import io.oryxos.core.agent.AgentSession;
import io.oryxos.core.agent.ContextLoader;
import io.oryxos.core.memory.MemoryStoreException;
import io.oryxos.core.profile.AgentProfile;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds long-term memory to the system prompt of agents that opted in by listing a memory tool in
 * their Profile. Conversation history is NOT repeated here: the Session already supplies it. A
 * broken memory backend degrades to "no memory" for that turn instead of failing the conversation.
 */
public final class MemoryContextLoader implements ContextLoader {
  private static final Logger LOG = LoggerFactory.getLogger(MemoryContextLoader.class);
  private final MemoryServices memories;

  public MemoryContextLoader(MemoryService memory) {
    this(MemoryServices.single(memory));
  }

  public MemoryContextLoader(MemoryServices memories) {
    this.memories = Objects.requireNonNull(memories, "memories");
  }

  @Override
  public String load(AgentProfile profile, AgentSession session) {
    boolean optedIn =
        profile.tools().contains("save_memory") || profile.tools().contains("recall_memory");
    if (!optedIn) {
      return "";
    }
    try {
      return memories.forAgent(profile.name()).promptSection();
    } catch (MemoryStoreException e) {
      LOG.warn("Long-term memory unavailable for this turn: {}", e.getMessage());
      return "";
    }
  }
}
