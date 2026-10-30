package io.oryxos.core.profile;

/**
 * The request cannot be applied to the agent as it stands: the name is taken (including by a
 * read-only legacy profile), or the agent is not of the kind that may be changed this way.
 */
public class AgentConflictException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public AgentConflictException(String message) {
    super(message);
  }
}
