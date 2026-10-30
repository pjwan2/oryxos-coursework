package io.oryxos.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.memory.MemoryEntry;
import io.oryxos.core.memory.MemoryIsolationUnsupportedException;
import io.oryxos.core.memory.MemoryStoreException;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolContext;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** {@code recall_memory}: keyword search over ARCHIVAL memories only. */
public final class RecallMemoryTool implements OryxTool {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final MemoryServices memories;

  public RecallMemoryTool(MemoryService memory) {
    this(MemoryServices.single(memory));
  }

  /** Each call uses the memory of the agent named in its {@link ToolContext}. */
  public RecallMemoryTool(MemoryServices memories) {
    this.memories = Objects.requireNonNull(memories, "memories");
  }

  @Override
  public String getName() {
    return "recall_memory";
  }

  @Override
  public String getDescription() {
    return "Search archived memories by keyword (core memories are already in context)";
  }

  @Override
  public String getInputSchema() {
    return "{\"type\":\"object\",\"properties\":{\"keyword\":{\"type\":\"string\"}},"
        + "\"required\":[\"keyword\"]}";
  }

  @Override
  public ToolOutput execute(String argumentsJson, ToolContext context) {
    try {
      JsonNode args =
          MAPPER.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
      JsonNode keyword = args.get("keyword");
      if (keyword == null || !keyword.isTextual()) {
        return ToolOutput.failed("keyword is required", false);
      }
      List<MemoryEntry> found =
          memories.forAgent(context.profile().name()).recall(keyword.asText());
      if (found.isEmpty()) {
        return ToolOutput.ok("No matching archived memories");
      }
      return ToolOutput.ok(
          found.stream().map(e -> "- " + e.content()).collect(Collectors.joining("\n")));
    } catch (JsonProcessingException e) {
      return ToolOutput.failed("arguments are not valid JSON", false);
    } catch (IllegalArgumentException invalid) {
      return ToolOutput.failed(invalid.getMessage(), false);
    } catch (MemoryIsolationUnsupportedException unsupported) {
      return ToolOutput.failed(unsupported.getMessage(), false);
    } catch (MemoryStoreException storage) {
      return ToolOutput.failed("Memory storage failed", true);
    }
  }
}
