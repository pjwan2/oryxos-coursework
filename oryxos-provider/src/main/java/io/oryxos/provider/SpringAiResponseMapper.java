package io.oryxos.provider;

import io.oryxos.core.agent.ModelTurn;
import io.oryxos.core.agent.ToolAction;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Pure data adapter: Spring AI {@link ChatResponse} to the ReAct loop's {@link ModelTurn}. It never
 * executes a tool; execution stays with ReActLoop and the tool executor.
 */
public final class SpringAiResponseMapper {
  private static final String NO_ARGUMENTS = "{}";

  public ModelTurn map(ChatResponse response) {
    if (response == null) {
      throw new IllegalStateException("LLM response is null");
    }
    if (response.getResult() == null) {
      throw new IllegalStateException("LLM response has no generation");
    }
    AssistantMessage output = response.getResult().getOutput();
    if (output == null) {
      throw new IllegalStateException("LLM response has no assistant message");
    }
    String text = output.getText() == null ? "" : output.getText();
    List<AssistantMessage.ToolCall> calls = output.getToolCalls();
    if (calls == null || calls.isEmpty()) {
      return ModelTurn.finalAnswer(text);
    }
    List<ToolAction> actions = new ArrayList<>(calls.size());
    for (int index = 0; index < calls.size(); index++) {
      actions.add(toAction(calls.get(index), index));
    }
    return new ModelTurn(text, actions);
  }

  private static ToolAction toAction(AssistantMessage.ToolCall call, int index) {
    if (call == null) {
      throw new IllegalStateException("LLM tool call #" + index + " is null");
    }
    if (call.id() == null || call.id().isBlank()) {
      throw new IllegalStateException("LLM tool call #" + index + " has no id");
    }
    if (call.name() == null || call.name().isBlank()) {
      throw new IllegalStateException("LLM tool call #" + index + " has no tool name");
    }
    // Tools without parameters may arrive with null or empty arguments.
    String arguments =
        call.arguments() == null || call.arguments().isBlank() ? NO_ARGUMENTS : call.arguments();
    return new ToolAction(call.id(), call.name(), arguments);
  }
}
