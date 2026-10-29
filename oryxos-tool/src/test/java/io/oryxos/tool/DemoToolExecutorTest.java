package io.oryxos.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.ToolAction;
import io.oryxos.core.profile.AgentProfile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DemoToolExecutorTest {
  private static AgentProfile profile(List<String> allowed) {
    return new AgentProfile(
        "demo",
        "",
        Map.of(),
        new AgentProfile.ProviderSettings("mock", "m", 0.1),
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
  void allowedReadOnlyToolReturnsData() {
    var executor = new DemoToolExecutor();
    var result =
        executor.execute(
            "s1",
            profile(List.of("get_payment")),
            new ToolAction("t1", "get_payment", "{\"payment_id\":\"P1001\"}"));
    assertTrue(result.success());
    assertTrue(result.content().contains("PENDING"));
  }

  @Test
  void toolPermissionIsCheckedBeforeExecution() {
    var executor = new DemoToolExecutor();
    assertThrows(
        SecurityException.class,
        () ->
            executor.execute(
                "s2",
                profile(List.of()),
                new ToolAction("t1", "get_payment", "{\"payment_id\":\"P1001\"}")));
    assertTrue(executor.allowedSpecifications(profile(List.of())).isEmpty());
    assertEquals(1, executor.allowedSpecifications(profile(List.of("get_payment"))).size());
  }

  @Test
  void invalidOrUnknownPaymentFailsClosed() {
    var executor = new DemoToolExecutor();
    assertFalse(
        executor
            .execute("s", profile(List.of("get_payment")), new ToolAction("t", "get_payment", "{}"))
            .success());
    assertFalse(
        executor
            .execute(
                "s",
                profile(List.of("get_payment")),
                new ToolAction("t", "get_payment", "{\"payment_id\":\"P9999\"}"))
            .success());
  }
}
