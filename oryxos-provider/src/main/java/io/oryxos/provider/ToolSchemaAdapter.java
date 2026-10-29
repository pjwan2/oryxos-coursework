package io.oryxos.provider;

import io.oryxos.core.tool.ToolSpec;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

/** Adapts schemas for model consumption. Never executes a tool. */
@Component
public class ToolSchemaAdapter {

  public List<ToolCallback> toSpringAiTools(List<ToolSpec> specifications) {
    return specifications.stream()
        .map(spec -> (ToolCallback) new DescriptionOnlyCallback(spec))
        .toList();
  }

  private static final class DescriptionOnlyCallback implements ToolCallback {
    private final ToolDefinition definition;

    DescriptionOnlyCallback(ToolSpec spec) {
      this.definition =
          new DefaultToolDefinition(spec.name(), spec.description(), spec.inputSchema());
    }

    @Override
    public ToolDefinition getToolDefinition() {
      return definition;
    }

    @Override
    public String call(String input) {
      throw new SecurityException("Provider cannot execute tools; use ToolExecutor");
    }
  }
}
