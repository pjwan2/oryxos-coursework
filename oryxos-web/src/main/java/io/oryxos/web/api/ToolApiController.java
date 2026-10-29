package io.oryxos.web.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import io.oryxos.core.agent.ToolCallHistory;
import io.oryxos.core.agent.ToolCallRecord;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.core.tool.ToolSpec;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registered tools (what the model is told about) and the audit of calls that actually happened.
 * The registry is global; which agent may use which tool is decided by each profile.
 */
@Tag(name = "Tool")
@RestController
@RequestMapping("/api/v1")
public class ToolApiController {
  static final int DEFAULT_CALLS = 50;
  static final int MAX_CALLS = 200;

  private final ToolRegistry registry;
  private final ToolCallHistory history;
  private final ObjectMapper mapper;

  public ToolApiController(ToolRegistry registry, ToolCallHistory history, ObjectMapper mapper) {
    this.registry = registry;
    this.history = history;
    this.mapper = mapper.copy();
  }

  /** {@code inputSchema} is the tool's JSON Schema as an object (plain text if it is not JSON). */
  public record ToolView(String name, String description, JsonNode inputSchema) {}

  @GetMapping("/tools")
  public ApiResponse<List<ToolView>> tools() {
    return ApiResponse.ok(registry.specs().stream().map(this::view).toList());
  }

  /** Newest first. Filter by session and/or an inclusive ISO-8601 time window. */
  @GetMapping("/tool-calls")
  public ApiResponse<List<ToolCallRecord>> toolCalls(
      @RequestParam(name = "sessionId", required = false) String sessionId,
      @RequestParam(name = "since", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant since,
      @RequestParam(name = "until", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant until,
      @RequestParam(name = "limit", required = false) Integer limit,
      HttpServletRequest request) {
    LocalAccess.require(request);
    if (sessionId != null) {
      SessionRef.parse(sessionId);
    }
    int effective = limit == null ? DEFAULT_CALLS : Math.min(Math.max(limit, 1), MAX_CALLS);
    return ApiResponse.ok(history.find(sessionId, since, until, effective));
  }

  private ToolView view(ToolSpec spec) {
    JsonNode schema;
    try {
      schema = mapper.readTree(spec.inputSchema());
    } catch (JsonProcessingException e) {
      schema = TextNode.valueOf(spec.inputSchema());
    }
    return new ToolView(spec.name(), spec.description(), schema);
  }
}
