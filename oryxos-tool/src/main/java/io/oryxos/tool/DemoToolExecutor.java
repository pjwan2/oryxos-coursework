package io.oryxos.tool;

import io.oryxos.core.agent.AgentToolExecutor;
import io.oryxos.core.agent.ToolAction;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.tool.ToolSpec;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only teaching tool. No shell, network, filesystem or payment mutations. */
public final class DemoToolExecutor implements AgentToolExecutor {
  private static final Pattern PAYMENT = Pattern.compile("\"payment_id\"\\s*:\\s*\"(P[0-9]+)\"");
  private static final ToolSpec LOOKUP =
      new ToolSpec(
          "get_payment",
          "Lookup a demo payment",
          "{\"type\":\"object\",\"properties\":{\"payment_id\":{\"type\":\"string\"}},\"required\":[\"payment_id\"]}");
  private static final Map<String, String> SAMPLE =
      Map.of("P1001", "P1001 PENDING 120 AUD", "P1002", "P1002 COMPLETED 80 AUD");

  @Override
  public List<ToolSpec> allowedSpecifications(AgentProfile profile) {
    Objects.requireNonNull(profile, "profile");
    return profile.tools().contains(LOOKUP.name()) ? List.of(LOOKUP) : List.of();
  }

  @Override
  public ToolOutput execute(String sessionId, AgentProfile profile, ToolAction action) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(action, "action");
    if (!profile.tools().contains(action.name()) || !LOOKUP.name().equals(action.name())) {
      throw new SecurityException("Tool not permitted: " + action.name());
    }
    Matcher matched = PAYMENT.matcher(action.argumentsJson());
    if (!matched.find()) {
      return ToolOutput.failed("payment_id is required", false);
    }
    String value = SAMPLE.get(matched.group(1));
    return value == null ? ToolOutput.failed("Payment not found", false) : ToolOutput.ok(value);
  }
}
