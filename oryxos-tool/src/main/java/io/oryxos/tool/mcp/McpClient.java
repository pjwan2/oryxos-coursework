package io.oryxos.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Speaks the three MCP calls we need: initialize, tools/list, tools/call. */
public final class McpClient implements Closeable {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final McpTransport transport;

  public McpClient(McpTransport transport) {
    this.transport = Objects.requireNonNull(transport, "transport");
  }

  public record RemoteTool(String name, String description, String inputSchema) {}

  public record CallResult(boolean error, String text) {}

  public void initialize() throws IOException {
    ObjectNode params = MAPPER.createObjectNode();
    params.put("protocolVersion", "2024-11-05");
    params.putObject("capabilities");
    params.putObject("clientInfo").put("name", "oryxos").put("version", "0.1.0");
    transport.request("initialize", params);
  }

  public List<RemoteTool> listTools() throws IOException {
    JsonNode result = transport.request("tools/list", MAPPER.createObjectNode());
    List<RemoteTool> tools = new ArrayList<>();
    for (JsonNode node : result.path("tools")) {
      String name = node.path("name").asText("");
      if (name.isBlank()) {
        continue;
      }
      JsonNode schema = node.path("inputSchema");
      tools.add(
          new RemoteTool(
              name,
              node.path("description").asText(name),
              schema.isMissingNode() ? "{\"type\":\"object\"}" : schema.toString()));
    }
    return List.copyOf(tools);
  }

  public CallResult callTool(String name, JsonNode arguments) throws IOException {
    ObjectNode params = MAPPER.createObjectNode();
    params.put("name", name);
    params.set("arguments", arguments);
    JsonNode result = transport.request("tools/call", params);
    StringBuilder text = new StringBuilder();
    for (JsonNode part : result.path("content")) {
      if ("text".equals(part.path("type").asText())) {
        text.append(part.path("text").asText());
      }
    }
    return new CallResult(result.path("isError").asBoolean(false), text.toString());
  }

  @Override
  public void close() throws IOException {
    transport.close();
  }
}
