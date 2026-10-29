package io.oryxos.cli;

import io.oryxos.core.agent.AgentMessage;
import io.oryxos.core.agent.ModelGateway;
import io.oryxos.core.agent.ModelTurn;
import io.oryxos.core.agent.ToolAction;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.tool.ToolSpec;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic lesson-only mock. No real Spring AI / LLM tool calling. */
public final class PaymentScriptedGateway implements ModelGateway {
  private static final Pattern PAYMENT_ID = Pattern.compile("P[0-9]+");

  @Override
  public ModelTurn chat(
      String sessionId, AgentProfile profile, List<AgentMessage> prompt, List<ToolSpec> tools) {
    if (!"mock".equals(profile.provider().name())) {
      throw new IllegalStateException("Scripted lab only supports mock provider");
    }
    AgentMessage last = prompt.getLast();
    if (last.role() == AgentMessage.Role.TOOL) {
      if (last.content().contains(" ERROR ")) {
        return ModelTurn.finalAnswer("查询失败：" + last.content());
      }
      return ModelTurn.finalAnswer("查询完成：" + last.content());
    }
    Matcher matched = PAYMENT_ID.matcher(last.content());
    if (!matched.find()) {
      return ModelTurn.finalAnswer("请提供支付单号");
    }
    // Tool schema is discoverable but not executed by this mock.
    boolean allowed = tools.stream().anyMatch(spec -> "get_payment".equals(spec.name()));
    if (!allowed) {
      return ModelTurn.finalAnswer("当前 Agent 未获得查询支付单权限");
    }
    return ModelTurn.tools(
        List.of(
            new ToolAction(
                "call-1", "get_payment", "{\"payment_id\":\"" + matched.group() + "\"}")));
  }
}
