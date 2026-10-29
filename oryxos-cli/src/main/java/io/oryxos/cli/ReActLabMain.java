package io.oryxos.cli;

import io.oryxos.core.agent.AgentMessage;
import io.oryxos.core.agent.AgentSession;
import io.oryxos.core.agent.ModelGateway;
import io.oryxos.core.agent.PromptBuilder;
import io.oryxos.core.agent.ReActLoop;
import io.oryxos.core.agent.ToolCallEvent;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.tool.AuditedToolExecutor;
import io.oryxos.tool.DemoToolExecutor;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Runnable demo, no credentials or network needed. Accepts P1001/P1002/P9999 as an argument. */
public final class ReActLabMain {
  private ReActLabMain() {}

  public static void main(String[] args) {
    String paymentId = args.length > 0 ? args[0] : "P1001";
    AgentProfile profile =
        new AgentProfile(
            "demo",
            "Payment demo",
            Map.of("prompt", "Demo assistant"),
            new AgentProfile.ProviderSettings("mock", "mock-echo", 0.2),
            List.of("get_payment"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            Map.of("max_iterations", 10));
    ModelGateway scripted = new PaymentScriptedGateway();
    AgentSession session = new AgentSession("demo-session", "demo");
    List<ToolCallEvent> audited = new ArrayList<>();
    var toolExecutor = new AuditedToolExecutor(new DemoToolExecutor(), audited::add);
    var loop = new ReActLoop(scripted, toolExecutor, new PromptBuilder(Clock.systemUTC(), 20));
    String answer = loop.run(session, "查询支付单 " + paymentId, profile);
    System.out.println("Final: " + answer);
    for (AgentMessage event : session.transcript()) {
      System.out.println(event.role() + ": " + event.content());
    }
    System.out.println(
        "Tool audit records: "
            + audited.size()
            + " (success="
            + audited.getFirst().success()
            + ")");
  }
}
