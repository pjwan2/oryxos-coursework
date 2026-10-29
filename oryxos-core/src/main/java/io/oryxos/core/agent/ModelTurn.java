package io.oryxos.core.agent;

import java.util.List;
import java.util.Objects;

/** Exactly one model response: a final text or one or more tool requests. */
public record ModelTurn(String text, List<ToolAction> actions) {
  public ModelTurn {
    Objects.requireNonNull(text, "text");
    actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
  }

  @Override
  public List<ToolAction> actions() {
    return List.copyOf(actions);
  }

  public static ModelTurn finalAnswer(String text) {
    return new ModelTurn(text, List.of());
  }

  public static ModelTurn tools(List<ToolAction> actions) {
    return new ModelTurn("", actions);
  }
}
