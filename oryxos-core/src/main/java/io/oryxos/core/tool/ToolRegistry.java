package io.oryxos.core.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable-after-build lookup of tools by name. Duplicate names are a startup error. */
public final class ToolRegistry {
  private final Map<String, OryxTool> tools;

  public ToolRegistry(Collection<? extends OryxTool> candidates) {
    Map<String, OryxTool> byName = new LinkedHashMap<>();
    for (OryxTool tool : Objects.requireNonNull(candidates, "candidates")) {
      validate(tool);
      if (byName.putIfAbsent(tool.getName(), tool) != null) {
        throw new IllegalArgumentException("Duplicate tool name: " + tool.getName());
      }
    }
    this.tools = Map.copyOf(byName);
  }

  private static void validate(OryxTool tool) {
    Objects.requireNonNull(tool, "tool");
    if (isBlank(tool.getName()) || isBlank(tool.getDescription())) {
      throw new IllegalArgumentException("Tool needs a name and a description");
    }
    if (isBlank(tool.getInputSchema())) {
      throw new IllegalArgumentException("Tool " + tool.getName() + " has no input schema");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  public Optional<OryxTool> find(String name) {
    return Optional.ofNullable(tools.get(name));
  }

  public List<String> names() {
    return tools.keySet().stream().sorted().toList();
  }

  public List<ToolSpec> specs() {
    List<ToolSpec> result = new ArrayList<>();
    for (String name : names()) {
      result.add(tools.get(name).toSpec());
    }
    return List.copyOf(result);
  }
}
