package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.oryxos.core.tool.ToolSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolSchemaAdapterTest {
  @Test
  void translatesSchemaButNeverExecutes() {
    var callback =
        new ToolSchemaAdapter()
            .toSpringAiTools(
                List.of(new ToolSpec("http_get", "Fetch a URL", "{\"type\":\"object\"}")))
            .getFirst();
    assertEquals("http_get", callback.getToolDefinition().name());
    assertEquals("{\"type\":\"object\"}", callback.getToolDefinition().inputSchema());
    assertThrows(SecurityException.class, () -> callback.call("{}"));
  }
}
