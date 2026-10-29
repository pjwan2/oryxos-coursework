package io.oryxos.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.ToolAction;
import io.oryxos.core.agent.ToolCallEvent;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.sandbox.SandboxViolation;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolContext;
import io.oryxos.core.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RegistryToolExecutorTest {
  private static AgentProfile profile(String... tools) {
    return new AgentProfile(
        "p",
        "d",
        Map.of(),
        new AgentProfile.ProviderSettings("mock", "m", 0.1),
        List.of(tools),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        Map.of());
  }

  private static OryxTool tool(String name, java.util.function.Supplier<ToolOutput> body) {
    return new OryxTool() {
      public String getName() {
        return name;
      }

      public String getDescription() {
        return "d";
      }

      public String getInputSchema() {
        return "{}";
      }

      public ToolOutput execute(String args, ToolContext ctx) {
        return body.get();
      }
    };
  }

  private static RegistryToolExecutor executor(OryxTool... tools) {
    return new RegistryToolExecutor(new ToolRegistry(List.of(tools)));
  }

  @Test
  void onlyProfileListedToolsAreAdvertisedAndExecutable() {
    var ex = executor(tool("a", () -> ToolOutput.ok("A")), tool("b", () -> ToolOutput.ok("B")));
    assertEquals(1, ex.allowedSpecifications(profile("a")).size());
    assertEquals("A", ex.execute("s", profile("a"), new ToolAction("1", "a", "{}")).content());
    assertThrows(
        SecurityException.class,
        () -> ex.execute("s", profile("a"), new ToolAction("2", "b", "{}")));
  }

  @Test
  void listedButNotInstalledToolFailsWithoutThrowing() {
    var ex = executor(tool("a", () -> ToolOutput.ok("A")));
    ToolOutput out = ex.execute("s", profile("ghost"), new ToolAction("1", "ghost", "{}"));
    assertFalse(out.success());
  }

  @Test
  void sandboxViolationBecomesDeniedAndOtherBugsBecomeFailures() {
    var ex =
        executor(
            tool(
                "deny",
                () -> {
                  throw new SandboxViolation("nope");
                }),
            tool(
                "bug",
                () -> {
                  throw new IllegalStateException("secret detail");
                }));
    ToolOutput denied = ex.execute("s", profile("deny"), new ToolAction("1", "deny", "{}"));
    assertTrue(denied.isDenied());
    ToolOutput bug = ex.execute("s", profile("bug"), new ToolAction("2", "bug", "{}"));
    assertFalse(bug.success());
    assertFalse(bug.errorMessage().contains("secret detail"));
  }

  @Test
  void auditDistinguishesSandboxDenialFromOrdinaryFailure() {
    List<ToolCallEvent> events = new ArrayList<>();
    var audited =
        new AuditedToolExecutor(
            executor(
                tool("deny", () -> ToolOutput.denied("outside root")),
                tool("fail", () -> ToolOutput.failed("x", false)),
                tool("good", () -> ToolOutput.ok("ok"))),
            events::add);
    var p = profile("deny", "fail", "good");
    audited.execute("s", p, new ToolAction("1", "deny", "{}"));
    audited.execute("s", p, new ToolAction("2", "fail", "{}"));
    audited.execute("s", p, new ToolAction("3", "good", "{}"));
    assertEquals("SandboxDenied", events.get(0).errorCategory());
    assertEquals("ToolReturnedFailure", events.get(1).errorCategory());
    assertTrue(events.get(2).success());
  }
}
