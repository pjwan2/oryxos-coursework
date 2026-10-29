package io.oryxos.tool;

import io.oryxos.core.sandbox.Sandbox;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.tool.notify.NotifyTool;
import io.oryxos.tool.notify.WebhookNotifyAdapter;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/** The one list of built-in tools, shared by the Spring wiring and the lightweight CLI. */
public final class BuiltinTools {
  private BuiltinTools() {}

  public static List<OryxTool> create(Sandbox sandbox, HttpClient http, Duration shellTimeout) {
    return List.of(
        new GetPaymentTool(),
        new FileReadTool(sandbox),
        new FileWriteTool(sandbox),
        new HttpGetTool(sandbox, http),
        new ShellTool(sandbox, shellTimeout),
        new NotifyTool(List.of(new WebhookNotifyAdapter(http)), sandbox));
  }

  /** Deny-all instances, enough to list names and schemas without any side effect. */
  public static List<OryxTool> describeOnly() {
    return create(
        new AllowListSandbox(SandboxPolicy.denyAll()),
        HttpGetTool.defaultClient(),
        Duration.ofSeconds(1));
  }
}
