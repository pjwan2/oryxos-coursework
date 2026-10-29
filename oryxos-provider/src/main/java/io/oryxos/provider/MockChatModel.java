package io.oryxos.provider;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/** Deterministic network-free model for wiring checks, not for real AI use. */
public class MockChatModel implements ChatModel {
  @Override
  public ChatResponse call(Prompt prompt) {
    String last =
        prompt.getInstructions().isEmpty() ? "" : prompt.getInstructions().getLast().getText();
    return new ChatResponse(
        List.of(new Generation(new AssistantMessage("Mock response: " + last))));
  }
}
