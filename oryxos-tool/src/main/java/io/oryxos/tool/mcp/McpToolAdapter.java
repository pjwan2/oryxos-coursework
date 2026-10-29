package io.oryxos.tool.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolContext;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

/** Exposes one remote MCP tool as a local OryxTool named mcp_&lt;server&gt;_&lt;tool&gt;. */
public final class McpToolAdapter implements OryxTool {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final String localName;
  private final McpClient.RemoteTool remote;
  private final McpClient client;

  public McpToolAdapter(String serverName, McpClient.RemoteTool remote, McpClient client) {
    this.remote = Objects.requireNonNull(remote, "remote");
    this.client = Objects.requireNonNull(client, "client");
    this.localName = localName(serverName, remote.name());
  }

  static String localName(String server, String tool) {
    String raw = ("mcp_" + server + "_" + tool).toLowerCase(Locale.ROOT);
    String safe = raw.replaceAll("[^a-z0-9_-]", "_");
    return safe.length() > 64 ? safe.substring(0, 64) : safe;
  }

  @Override
  public String getName() {
    return localName;
  }

  @Override
  public String getDescription() {
    return remote.description();
  }

  @Override
  public String getInputSchema() {
    return remote.inputSchema();
  }

  @Override
  public ToolOutput execute(String argumentsJson, ToolContext context) {
    JsonNode args;
    try {
      args =
          MAPPER.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
    } catch (JsonProcessingException e) {
      return ToolOutput.failed("arguments are not valid JSON", false);
    }
    try {
      McpClient.CallResult result = client.callTool(remote.name(), args);
      return result.error()
          ? ToolOutput.failed("MCP tool error: " + result.text(), false)
          : ToolOutput.ok(result.text());
    } catch (IOException unreachable) {
      // The server may have died after startup: report a retryable failure, do not crash.
      return ToolOutput.failed(
          "MCP server unavailable: " + unreachable.getClass().getSimpleName(), true);
    }
  }
}
