package io.oryxos.tool.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.sandbox.Sandbox;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agent-facing "notify" tool. The model may only pick a target NAME; the URL always comes from the
 * Profile, so a prompt cannot redirect a notification to an arbitrary address.
 */
public final class NotifyTool implements OryxTool {
  static final int MAX_MESSAGE_CHARS = 4000;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final Map<String, NotifyChannelAdapter> adapters = new HashMap<>();
  private final Sandbox sandbox;

  public NotifyTool(List<NotifyChannelAdapter> adapterList, Sandbox sandbox) {
    this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
    for (NotifyChannelAdapter adapter : Objects.requireNonNull(adapterList, "adapters")) {
      adapters.put(adapter.type(), adapter);
    }
  }

  @Override
  public String getName() {
    return "notify";
  }

  @Override
  public String getDescription() {
    return "Send a notification to one of the agent's configured notify channels";
  }

  @Override
  public String getInputSchema() {
    return "{\"type\":\"object\",\"properties\":{\"target\":{\"type\":\"string\"},"
        + "\"title\":{\"type\":\"string\"},\"message\":{\"type\":\"string\"}},"
        + "\"required\":[\"target\",\"message\"]}";
  }

  @Override
  public ToolOutput execute(String argumentsJson, ToolContext context) {
    JsonNode args;
    try {
      args =
          MAPPER.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return ToolOutput.failed("arguments are not valid JSON", false);
    }
    String targetName = text(args, "target");
    String message = text(args, "message");
    if (targetName.isBlank() || message.isBlank()) {
      return ToolOutput.failed("target and message are required", false);
    }
    if (message.length() > MAX_MESSAGE_CHARS) {
      return ToolOutput.failed("message too long", false);
    }
    NotifyTarget target = findTarget(context, targetName);
    if (target == null) {
      return ToolOutput.failed("Unknown notify target: " + targetName, false);
    }
    NotifyChannelAdapter adapter = adapters.get(target.type());
    if (adapter == null) {
      return ToolOutput.failed("Unsupported notify type: " + target.type(), false);
    }
    // Scoped to THIS call's Profile: another agent's permissions are never visible here.
    NotifyChannelAdapter.NotifyResult result =
        adapter.send(sandbox.forProfile(context.profile()), target, text(args, "title"), message);
    if (result.success()) {
      return ToolOutput.ok("Notification sent to " + targetName);
    }
    if (result.detail().startsWith("SANDBOX_DENIED: ")) {
      return ToolOutput.denied(result.detail().substring("SANDBOX_DENIED: ".length()));
    }
    return ToolOutput.failed("Notification failed: " + result.detail(), result.retryable());
  }

  private static NotifyTarget findTarget(ToolContext context, String name) {
    for (Map<String, Object> entry : context.profile().notifyChannels()) {
      if (name.equals(String.valueOf(entry.get("name")))) {
        try {
          return NotifyTarget.from(entry);
        } catch (IllegalArgumentException incomplete) {
          return null;
        }
      }
    }
    return null;
  }

  private static String text(JsonNode args, String field) {
    JsonNode value = args.get(field);
    return value != null && value.isTextual() ? value.asText() : "";
  }
}
