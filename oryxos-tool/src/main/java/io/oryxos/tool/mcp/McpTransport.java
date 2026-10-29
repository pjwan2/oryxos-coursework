package io.oryxos.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.Closeable;
import java.io.IOException;

/** Minimal JSON-RPC request/response channel to one MCP server. */
public interface McpTransport extends Closeable {
  /** Sends one request and returns its "result" node; throws IOException on any failure. */
  JsonNode request(String method, JsonNode params) throws IOException;
}
