package io.oryxos.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.ToolAction;
import io.oryxos.core.agent.ToolCallEvent;
import io.oryxos.core.profile.AgentProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditedToolExecutorTest {
  static AgentProfile profile(List<String> allowed) {
    return new AgentProfile(
        "demo",
        "",
        Map.of(),
        new AgentProfile.ProviderSettings("mock", "mock", 0.2),
        allowed,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        Map.of());
  }

  @Test
  void successFailureAndDeniedAreAllAuditedOnce() {
    List<ToolCallEvent> events = new ArrayList<>();
    var tools = new AuditedToolExecutor(new DemoToolExecutor(), events::add);
    var allowed = profile(List.of("get_payment"));
    assertTrue(
        tools
            .execute("s", allowed, new ToolAction("1", "get_payment", "{\"payment_id\":\"P1001\"}"))
            .success());
    assertFalse(
        tools
            .execute("s", allowed, new ToolAction("2", "get_payment", "{\"payment_id\":\"P9999\"}"))
            .success());
    assertThrows(
        SecurityException.class,
        () ->
            tools.execute(
                "s",
                profile(List.of()),
                new ToolAction("3", "get_payment", "{\"payment_id\":\"P1001\"}")));
    assertEquals(3, events.size());
    assertTrue(events.get(0).success());
    assertFalse(events.get(1).success());
    assertEquals("SecurityException", events.get(2).errorCategory());
  }
}
