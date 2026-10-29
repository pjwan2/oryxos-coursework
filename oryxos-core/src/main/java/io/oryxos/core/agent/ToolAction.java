package io.oryxos.core.agent;

import java.util.Objects;

/** Model-requested action; the model never executes it directly. */
public record ToolAction(String id, String name, String argumentsJson) {
  public ToolAction {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(argumentsJson, "argumentsJson");
    if (name.isBlank()) {
      throw new IllegalArgumentException("Tool name is blank");
    }
  }
}
