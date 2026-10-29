package io.oryxos.tool;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolContext;
import java.util.Map;

/** Read-only teaching tool backed by fixed sample data. */
public final class GetPaymentTool implements OryxTool {
  public static final String NAME = "get_payment";
  private static final Map<String, String> SAMPLE =
      Map.of("P1001", "P1001 PENDING 120 AUD", "P1002", "P1002 COMPLETED 80 AUD");

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return "Lookup a demo payment";
  }

  @Override
  public String getInputSchema() {
    return "{\"type\":\"object\",\"properties\":{\"payment_id\":{\"type\":\"string\"}},"
        + "\"required\":[\"payment_id\"]}";
  }

  @Override
  public ToolOutput execute(String argumentsJson, ToolContext context) {
    JsonNode args;
    try {
      args = ToolArgs.parse(argumentsJson);
    } catch (IllegalArgumentException e) {
      return ToolOutput.failed("payment_id is required", false);
    }
    JsonNode id = args.get("payment_id");
    if (id == null || !id.isTextual() || !id.asText().matches("P[0-9]+")) {
      return ToolOutput.failed("payment_id is required", false);
    }
    String value = SAMPLE.get(id.asText());
    return value == null ? ToolOutput.failed("Payment not found", false) : ToolOutput.ok(value);
  }
}
