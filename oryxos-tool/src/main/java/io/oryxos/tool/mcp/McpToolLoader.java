package io.oryxos.tool.mcp;

import io.oryxos.core.tool.OryxTool;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connects to every configured MCP server and wraps its tools. A server that cannot be reached is
 * logged and skipped: an unreachable MCP server must never stop the application from starting.
 */
public final class McpToolLoader {
  private static final Logger LOG = LoggerFactory.getLogger(McpToolLoader.class);

  /** Opens one server; may throw IOException or RuntimeException when the server is down. */
  @FunctionalInterface
  public interface Connector {
    McpClient connect() throws IOException;
  }

  public record LoadReport(List<OryxTool> tools, List<String> failedServers) {
    public LoadReport {
      tools = List.copyOf(tools);
      failedServers = List.copyOf(failedServers);
    }

    @Override
    public List<OryxTool> tools() {
      return List.copyOf(tools);
    }

    @Override
    public List<String> failedServers() {
      return List.copyOf(failedServers);
    }
  }

  public LoadReport load(Map<String, Connector> servers) {
    List<OryxTool> tools = new ArrayList<>();
    List<String> failed = new ArrayList<>();
    servers.forEach(
        (name, connector) -> {
          try {
            McpClient client = connector.connect();
            try {
              client.initialize();
              for (McpClient.RemoteTool remote : client.listTools()) {
                tools.add(new McpToolAdapter(name, remote, client));
              }
            } catch (IOException | RuntimeException e) {
              closeQuietly(client);
              throw e;
            }
          } catch (IOException | RuntimeException e) {
            LOG.warn("MCP server '{}' unavailable, skipping: {}", name, e.toString());
            failed.add(name);
          }
        });
    return new LoadReport(List.copyOf(tools), List.copyOf(failed));
  }

  private static void closeQuietly(McpClient client) {
    try {
      client.close();
    } catch (IOException ignored) {
      // Nothing more can be done for a server that is already failing.
    }
  }
}
