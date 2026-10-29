package io.oryxos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.tool.ToolContext;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ToolContext CTX = new ToolContext("s", AgentProfile.example("mock", "m"));

  /** In-memory MCP server: one tool "echo" that returns its "text" argument. */
  private static McpTransport fakeServer(boolean failCalls) {
    return new McpTransport() {
      @Override
      public JsonNode request(String method, JsonNode params) throws IOException {
        return switch (method) {
          case "initialize" -> MAPPER.createObjectNode();
          case "tools/list" ->
              read(
                  "{\"tools\":[{\"name\":\"echo\",\"description\":\"Echo text\","
                      + "\"inputSchema\":{\"type\":\"object\"}}]}");
          case "tools/call" -> {
            if (failCalls) {
              throw new IOException("server died");
            }
            String text = params.path("arguments").path("text").asText();
            yield read(
                "{\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],\"isError\":false}");
          }
          default -> throw new IOException("unknown method " + method);
        };
      }

      @Override
      public void close() {}
    };
  }

  private static JsonNode read(String json) throws IOException {
    return MAPPER.readTree(json);
  }

  @Test
  void toolsAreDiscoveredWithPrefixedNamesAndCalledWithExactArguments() {
    var loader = new McpToolLoader();
    var report = loader.load(Map.of("My Server", () -> new McpClient(fakeServer(false))));
    assertEquals(1, report.tools().size());
    var tool = report.tools().getFirst();
    assertEquals("mcp_my_server_echo", tool.getName());
    assertFalse(tool.getInputSchema().isBlank());
    ToolOutput out = tool.execute("{\"text\":\"hi\"}", CTX);
    assertTrue(out.success());
    assertEquals("hi", out.content());
  }

  @Test
  void unreachableServerIsSkippedAndHealthyOnesStillLoad() {
    Map<String, McpToolLoader.Connector> servers = new LinkedHashMap<>();
    servers.put(
        "down",
        () -> {
          throw new IOException("connection refused");
        });
    servers.put(
        "crashy",
        () -> {
          throw new IllegalStateException("boom");
        });
    servers.put("good", () -> new McpClient(fakeServer(false)));
    var report = new McpToolLoader().load(servers);
    assertEquals(2, report.failedServers().size());
    assertEquals(1, report.tools().size());
  }

  @Test
  void serverThatDiesAfterStartupYieldsRetryableFailureNotAnException() {
    var report = new McpToolLoader().load(Map.of("s", () -> new McpClient(fakeServer(true))));
    ToolOutput out = report.tools().getFirst().execute("{\"text\":\"x\"}", CTX);
    assertFalse(out.success());
    assertTrue(out.retryable());
  }

  @Test
  void invalidArgumentJsonIsAFailure() {
    var report = new McpToolLoader().load(Map.of("s", () -> new McpClient(fakeServer(false))));
    assertFalse(report.tools().getFirst().execute("not json", CTX).success());
  }
}
