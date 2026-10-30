package io.oryxos.core.profile;

/** An agent definition (AGENT.md, a Profile, a schedule) is invalid; the text says why. */
public class AgentDefinitionException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public AgentDefinitionException(String message) {
    super(message);
  }

  public AgentDefinitionException(String message, Throwable cause) {
    super(message, cause);
  }
}
