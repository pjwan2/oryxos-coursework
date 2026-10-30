package io.oryxos.core.profile;

/** Asks the agent's model for draft files. Never saves, never runs anything it generates. */
public interface AgentDrafting {
  /** The model's proposal for {@code description}. Needs a configured, reachable provider. */
  AgentDraft generate(String name, String description);
}
