package io.oryxos.provider;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * Deterministic, network-free model for offline runs and tests, not for real AI use.
 *
 * <ul>
 *   <li>A user message {@code /tool <name> <json-arguments>} (or {@code tool:<name> <json>}, which
 *       Git Bash does not rewrite into a Windows path) makes it request that tool, provided the
 *       tool was advertised to the model (so the Profile allowlist still applies).
 *   <li>After a tool result it answers {@code Tool result: <data>}.
 *   <li>Anything else is echoed back as {@code Mock response: <text>}.
 * </ul>
 */
public class MockChatModel implements ChatModel {
  private static final List<String> TOOL_PREFIXES = List.of("/tool ", "tool:");

  @Override
  public ChatResponse call(Prompt prompt) {
    List<Message> messages = prompt.getInstructions();
    Message last = messages.isEmpty() ? null : messages.getLast();
    if (last instanceof ToolResponseMessage tool) {
      String data =
          tool.getResponses().stream()
              .map(ToolResponseMessage.ToolResponse::responseData)
              .collect(Collectors.joining("; "));
      return text("Tool result: " + data);
    }
    String content = last == null ? "" : last.getText();
    if (last instanceof UserMessage && content != null) {
      for (String prefix : TOOL_PREFIXES) {
        if (content.startsWith(prefix)) {
          return toolRequest(prompt, content.substring(prefix.length()).strip());
        }
      }
    }
    return text("Mock response: " + content);
  }

  private static ChatResponse toolRequest(Prompt prompt, String command) {
    int space = command.indexOf(' ');
    String name = space < 0 ? command : command.substring(0, space);
    String arguments = space < 0 ? "{}" : command.substring(space + 1).strip();
    boolean offered =
        prompt.getOptions() instanceof ToolCallingChatOptions options
            && options.getToolCallbacks().stream()
                .anyMatch(callback -> name.equals(callback.getToolDefinition().name()));
    if (!offered) {
      return text("Tool not available: " + name);
    }
    var call = new AssistantMessage.ToolCall("mock-call-" + name, "function", name, arguments);
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(call)).build())));
  }

  private static ChatResponse text(String value) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(value))));
  }
}
