package io.oryxos.provider;

import io.oryxos.core.agent.AgentMessage;
import io.oryxos.core.agent.ModelGateway;
import io.oryxos.core.agent.ModelTurn;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.tool.ToolSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * Bridges the course-owned ReAct loop to one Spring AI 1.1 ChatModel call. ProviderService
 * advertises tool schemas with automatic execution disabled. This adapter never executes tool
 * callbacks, nor does it hold session state.
 */
@Component
public final class SpringAiModelGateway implements ModelGateway {
  private final ProviderService providerService;
  private final SpringAiResponseMapper mapper = new SpringAiResponseMapper();

  public SpringAiModelGateway(ProviderService providerService) {
    this.providerService = Objects.requireNonNull(providerService, "providerService");
  }

  @Override
  public ModelTurn chat(
      String sessionId, AgentProfile profile, List<AgentMessage> transcript, List<ToolSpec> tools) {
    List<Message> messages = new ArrayList<>(transcript.size());
    for (AgentMessage item : transcript) {
      messages.add(toSpringAiMessage(item));
    }
    ChatResponse response = providerService.chat(sessionId, profile, new Prompt(messages), tools);
    return mapper.map(response);
  }

  /** Convert persisted, typed history to the native roles required by OpenAI-compatible APIs. */
  static Message toSpringAiMessage(AgentMessage item) {
    return switch (item.role()) {
      case SYSTEM -> new SystemMessage(item.content());
      case USER -> new UserMessage(item.content());
      case ASSISTANT -> {
        if (item.toolCalls().isEmpty()) {
          yield new AssistantMessage(item.content());
        }
        var calls =
            item.toolCalls().stream()
                .map(
                    a ->
                        new AssistantMessage.ToolCall(
                            a.id(), "function", a.name(), a.argumentsJson()))
                .toList();
        yield AssistantMessage.builder().content("").toolCalls(calls).build();
      }
      case TOOL -> {
        if (item.toolReplies().isEmpty()) {
          // Legacy v1.5 sessions stored tool results as a plain text step.
          // Do not forge a tool-response ID: send it as a context message instead.
          yield new UserMessage("Previous tool observation: " + item.content());
        }
        var replies =
            item.toolReplies().stream()
                .map(r -> new ToolResponseMessage.ToolResponse(r.id(), r.name(), r.responseData()))
                .toList();
        yield ToolResponseMessage.builder().responses(replies).build();
      }
    };
  }
}
