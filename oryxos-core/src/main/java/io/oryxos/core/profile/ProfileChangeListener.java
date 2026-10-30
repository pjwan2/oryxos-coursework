package io.oryxos.core.profile;

/**
 * Told after the registry has swapped a Profile in or out. {@code before} is null when the agent is
 * new, {@code after} is null when it was removed. Used to drop state derived from the old
 * definition (sandbox policy caches) so that a tightened permission applies at once.
 */
@FunctionalInterface
public interface ProfileChangeListener {
  void profileChanged(String name, AgentProfile before, AgentProfile after);
}
