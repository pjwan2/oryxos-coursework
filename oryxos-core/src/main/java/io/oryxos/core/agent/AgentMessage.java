package io.oryxos.core.agent;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Immutable conversation step. Native tool call IDs and arguments are persisted so a second Spring
 * AI model turn can receive real assistant/tool messages rather than loosely formatted text.
 */
public record AgentMessage(
    Role role, String content, List<ToolAction> toolCalls, List<ToolReply> toolReplies) {

  public enum Role {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
  }

  public AgentMessage(Role role, String content) {
    this(role, content, List.of(), List.of());
  }

  public AgentMessage {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(content, "content");
    // Null is accepted when reading session JSON created by v1.5, whose
    // AgentMessage record had only (role, content).
    toolCalls = List.copyOf(toolCalls == null ? List.of() : toolCalls);
    toolReplies = List.copyOf(toolReplies == null ? List.of() : toolReplies);
    if (!toolCalls.isEmpty() && role != Role.ASSISTANT) {
      throw new IllegalArgumentException("Tool calls must have ASSISTANT role");
    }
    if (!toolReplies.isEmpty() && role != Role.TOOL) {
      throw new IllegalArgumentException("Tool replies must have TOOL role");
    }
  }

  public static AgentMessage requests(List<ToolAction> actions) {
    List<ToolAction> snapshot = List.copyOf(actions);
    if (snapshot.isEmpty()) {
      throw new IllegalArgumentException("At least one tool call is required");
    }
    String summary =
        snapshot.stream()
            .map(a -> "TOOL_CALL " + a.id() + " " + a.name())
            .collect(Collectors.joining("\n"));
    return new AgentMessage(Role.ASSISTANT, summary, snapshot, List.of());
  }

  public static AgentMessage replies(List<ToolReply> replies) {
    List<ToolReply> snapshot = List.copyOf(replies);
    if (snapshot.isEmpty()) {
      throw new IllegalArgumentException("At least one tool response is required");
    }
    String summary =
        snapshot.stream()
            .map(r -> "TOOL_RESULT " + r.id() + " " + r.name() + " " + r.responseData())
            .collect(Collectors.joining("\n"));
    return new AgentMessage(Role.TOOL, summary, List.of(), snapshot);
  }

  public record ToolReply(String id, String name, String responseData) {
    public ToolReply {
      Objects.requireNonNull(id, "tool reply id");
      Objects.requireNonNull(name, "tool reply name");
      Objects.requireNonNull(responseData, "tool reply responseData");
    }
  }
}
