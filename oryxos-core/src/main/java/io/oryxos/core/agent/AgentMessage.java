package io.oryxos.core.agent;

import java.util.Objects;

/** Immutable step in the conversation. Tool results are preserved for the next model turn. */
public record AgentMessage(Role role, String content) {
  public enum Role {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
  }

  public AgentMessage {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(content, "content");
  }
}
