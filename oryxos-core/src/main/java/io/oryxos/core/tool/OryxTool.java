package io.oryxos.core.tool;

import io.oryxos.core.agent.ToolOutput;

/**
 * One executable capability. The input schema is mandatory because it is what the model sees. A
 * tool never decides whether it is allowed for an agent: ToolRegistry and the Profile allowlist do.
 */
public interface OryxTool {
  String getName();

  String getDescription();

  /** JSON Schema of the arguments object; never null or blank. */
  String getInputSchema();

  ToolOutput execute(String argumentsJson, ToolContext context);

  default ToolSpec toSpec() {
    return new ToolSpec(getName(), getDescription(), getInputSchema());
  }
}
