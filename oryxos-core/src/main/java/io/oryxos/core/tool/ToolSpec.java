package io.oryxos.core.tool;

/** An input-only tool description; this record has no executable implementation. */
public record ToolSpec(String name, String description, String inputSchema) {
  public ToolSpec {
    if (name == null
        || name.isBlank()
        || description == null
        || description.isBlank()
        || inputSchema == null
        || inputSchema.isBlank()) {
      throw new IllegalArgumentException(
          "Tool specification must have a name, description and JSON schema");
    }
  }
}
