package io.oryxos.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.memory.MemoryIsolationUnsupportedException;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryStoreException;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolContext;
import java.util.Objects;

/** {@code save_memory}: scope defaults to ARCHIVAL; CORE only when asked for explicitly. */
public final class SaveMemoryTool implements OryxTool {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final MemoryServices memories;

  public SaveMemoryTool(MemoryService memory) {
    this(MemoryServices.single(memory));
  }

  /** Each call uses the memory of the agent named in its {@link ToolContext}. */
  public SaveMemoryTool(MemoryServices memories) {
    this.memories = Objects.requireNonNull(memories, "memories");
  }

  @Override
  public String getName() {
    return "save_memory";
  }

  @Override
  public String getDescription() {
    return "Remember a fact for future conversations. scope ARCHIVAL (default) or CORE";
  }

  @Override
  public String getInputSchema() {
    return "{\"type\":\"object\",\"properties\":{\"content\":{\"type\":\"string\"},"
        + "\"scope\":{\"type\":\"string\",\"enum\":[\"ARCHIVAL\",\"CORE\"]}},"
        + "\"required\":[\"content\"]}";
  }

  @Override
  public ToolOutput execute(String argumentsJson, ToolContext context) {
    try {
      JsonNode args =
          MAPPER.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
      JsonNode content = args.get("content");
      if (content == null || !content.isTextual()) {
        return ToolOutput.failed("content is required", false);
      }
      JsonNode rawScope = args.get("scope");
      if (rawScope != null && !rawScope.isNull() && !rawScope.isTextual()) {
        return ToolOutput.failed("scope must be CORE or ARCHIVAL", false);
      }
      MemoryScope scope = MemoryScope.parseOrDefault(rawScope == null ? null : rawScope.asText());
      memories.forAgent(context.profile().name()).save(scope, content.asText());
      return ToolOutput.ok("Saved to " + scope + " memory");
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
