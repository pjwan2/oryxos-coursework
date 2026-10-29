package io.oryxos.tool.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.tool.ToolContext;
import io.oryxos.tool.AllowListSandbox;
import io.oryxos.tool.HttpGetTool;
import io.oryxos.tool.SandboxPolicy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotifyTest {
  private HttpServer server;
  private int port;
  private final List<String> received = new CopyOnWriteArrayList<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/hook",
        ex -> {
          received.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    server.createContext(
        "/broken",
        ex -> {
          ex.sendResponseHeaders(500, -1);
          ex.close();
        });
    server.start();
    port = server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private AllowListSandbox sandbox(Set<String> hosts) {
    return new AllowListSandbox(new SandboxPolicy(List.of(), List.of(), Set.of(), hosts));
  }

  private NotifyTool tool(Set<String> hosts) {
    return new NotifyTool(
        List.of(new WebhookNotifyAdapter(sandbox(hosts), HttpGetTool.defaultClient())));
  }

  private ToolContext ctx(String path) {
    Map<String, Object> channel =
        Map.of("name", "ops", "type", "webhook", "url", "http://127.0.0.1:" + port + path);
    var base = AgentProfile.example("mock", "m");
    var profile =
        new AgentProfile(
            base.name(),
            base.description(),
            Map.of(),
            base.provider(),
            List.of("notify"),
            List.of(),
            List.of(),
            List.of(),
            new ArrayList<>(List.of(channel)),
            List.of(),
            Map.of(),
            Map.of());
    return new ToolContext("s1", profile);
  }

  @Test
  void deliversJsonPayloadToTheProfileConfiguredWebhook() {
    ToolOutput out =
        tool(Set.of("127.0.0.1"))
            .execute("{\"target\":\"ops\",\"title\":\"T\",\"message\":\"中文 hello\"}", ctx("/hook"));
    assertTrue(out.success(), out.errorMessage());
    assertEquals(1, received.size());
    assertTrue(received.getFirst().contains("\"title\":\"T\""));
    assertTrue(received.getFirst().contains("中文 hello"));
  }

  @Test
  void non2xxIsReportedAsFailureNeverSuccess() {
    ToolOutput out =
        tool(Set.of("127.0.0.1")).execute("{\"target\":\"ops\",\"message\":\"m\"}", ctx("/broken"));
    assertFalse(out.success());
    assertTrue(out.retryable());
  }

  @Test
  void unreachableWebhookIsAFailure() {
    server.stop(0);
    ToolOutput out =
        tool(Set.of("127.0.0.1")).execute("{\"target\":\"ops\",\"message\":\"m\"}", ctx("/hook"));
    assertFalse(out.success());
  }

  @Test
  void sandboxBlocksHostBeforeAnyRequestIsSent() {
    ToolOutput out = tool(Set.of()).execute("{\"target\":\"ops\",\"message\":\"m\"}", ctx("/hook"));
    assertTrue(out.isDenied());
    assertTrue(received.isEmpty());
  }

  @Test
  void modelCannotChooseAnUrlOnlyAConfiguredTargetName() {
    ToolOutput out =
        tool(Set.of("127.0.0.1"))
            .execute("{\"target\":\"http://evil.invalid/x\",\"message\":\"m\"}", ctx("/hook"));
    assertFalse(out.success());
    assertTrue(received.isEmpty());
  }

  @Test
  void missingFieldsAndOversizedMessagesAreRejected() {
    var t = tool(Set.of("127.0.0.1"));
    assertFalse(t.execute("{\"target\":\"ops\"}", ctx("/hook")).success());
    assertFalse(t.execute("nope", ctx("/hook")).success());
    String big = "x".repeat(NotifyTool.MAX_MESSAGE_CHARS + 1);
    assertFalse(
        t.execute("{\"target\":\"ops\",\"message\":\"" + big + "\"}", ctx("/hook")).success());
  }
}
