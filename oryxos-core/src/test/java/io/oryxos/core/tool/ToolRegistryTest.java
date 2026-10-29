package io.oryxos.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.ToolOutput;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {
  private static OryxTool tool(String name, String schema) {
    return new OryxTool() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getDescription() {
        return "d-" + name;
      }

      @Override
      public String getInputSchema() {
        return schema;
      }

      @Override
      public ToolOutput execute(String argumentsJson, ToolContext context) {
        return ToolOutput.ok(name);
      }
    };
  }

  @Test
  void registersFindsAndExposesSortedSpecs() {
    var registry = new ToolRegistry(List.of(tool("b", "{}"), tool("a", "{}")));
    assertEquals(List.of("a", "b"), registry.names());
    assertTrue(registry.find("a").isPresent());
    assertTrue(registry.find("zzz").isEmpty());
    assertEquals("d-a", registry.specs().getFirst().description());
  }

  @Test
  void duplicateNamesAreRejected() {
    var tools = List.of(tool("a", "{}"), tool("a", "{}"));
    assertThrows(IllegalArgumentException.class, () -> new ToolRegistry(tools));
  }

  @Test
  void missingInputSchemaIsRejected() {
    var blank = List.of(tool("a", " "));
    var nulled = List.of(tool("a", null));
    assertThrows(IllegalArgumentException.class, () -> new ToolRegistry(blank));
    assertThrows(IllegalArgumentException.class, () -> new ToolRegistry(nulled));
  }
}
