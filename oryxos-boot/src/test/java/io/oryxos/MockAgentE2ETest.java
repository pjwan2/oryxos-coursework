package io.oryxos;

import static io.oryxos.LocalTestServers.Decision.call;
import static io.oryxos.LocalTestServers.Decision.say;
import static io.oryxos.LocalTestServers.lastContent;
import static io.oryxos.LocalTestServers.lastRole;
import static io.oryxos.LocalTestServers.systemContent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.LocalTestServers.Decision;
import io.oryxos.LocalTestServers.FakeModelServer;
import io.oryxos.LocalTestServers.WeatherFixture;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Lesson 27, whole system, without an API key or the public network.
 *
 * <p>Request -> REST -> {@code AgentService} -> ReAct loop -> the REAL Spring AI OpenAI client -> a
 * local server that only scripts the model's answers -> real {@code ToolExecutor} with sandbox ->
 * real memory file / local weather fixture -> real SQLite audit. Only the model's answer is faked;
 * nothing is inserted into {@code llm_calls} or {@code tool_invocations} by the test.
 *
 * <p>Every count is checked three ways: what the fake model server received over HTTP, what the
 * fixture received, and what is in the SQLite tables.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MockAgentE2ETest {
  private static final E2eWorkspace WS = new E2eWorkspace("oryxos-e2e-agent");
  private static final WeatherFixture WEATHER = new WeatherFixture();
  private static final FakeModelServer MODEL = new FakeModelServer(MockAgentE2ETest::brain, 0);

  /**
   * The scripted "model": a tool request for the first turn, a final answer after a tool result.
   */
  private static Decision brain(JsonNode request) {
    if ("tool".equals(lastRole(request))) {
      return say("Final answer from tool result: " + lastContent(request));
    }
    String user = lastContent(request);
    if (user.contains("天气")) {
      return call("http_get", "{\"url\":\"" + WEATHER.url() + "\"}");
    }
    if (user.contains("记住")) {
      return call("save_memory", "{\"content\":\"我喜欢喝美式咖啡\",\"scope\":\"CORE\"}");
    }
    if (user.contains("写文件")) {
      String outside = E2eWorkspace.slash(WS.forbidden.resolve("outside.txt"));
      return call("file_write", "{\"path\":\"" + outside + "\",\"content\":\"x\"}");
    }
    if (user.contains("爆炸")) {
      return call("explode", "{}");
    }
    return say("ok: " + user);
  }

  static {
    WS.profile(
        "weather-agent",
        E2eWorkspace.FAKE_PROVIDER,
        List.of("http_get"),
        E2eWorkspace.loopbackHttp(),
        null);
    WS.profile(
        "memory-agent",
        E2eWorkspace.FAKE_PROVIDER,
        List.of("save_memory", "recall_memory"),
        null,
        null);
    WS.profile(
        "sandbox-agent",
        E2eWorkspace.FAKE_PROVIDER,
        List.of("file_write"),
        E2eWorkspace.files(WS.allowed),
        null);
    WS.profile("explode-agent", E2eWorkspace.FAKE_PROVIDER, List.of("explode"), null, null);
    WS.profile("broken-agent", E2eWorkspace.UNREACHABLE_PROVIDER, List.of(), null, null);
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry registry) {
    WS.register(registry, MODEL.baseUrl());
  }

  @AfterAll
  static void stopServers() {
    MODEL.close();
    WEATHER.close();
  }

  /** A tool that fails with a message full of things that must never leak. */
  @TestConfiguration
  static class ExplodingTool {
    @Bean
    OryxTool explode() {
      return new OryxTool() {
        @Override
        public String getName() {
          return "explode";
        }

        @Override
        public String getDescription() {
          return "Always fails (test only)";
        }

        @Override
        public String getInputSchema() {
          return "{\"type\":\"object\"}";
        }

        @Override
        public ToolOutput execute(String argumentsJson, ToolContext context) {
          throw new IllegalStateException(
              "secret-token-123 jdbc:sqlite:/data/prod.db password=hunter2");
        }
      };
    }
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void resetCounters() {
    MODEL.reset();
    WEATHER.reset();
  }

  private record Reply(int status, JsonNode body, String raw) {}

  private Reply invoke(String agent, String user, String message) throws Exception {
    byte[] request =
        LocalTestServers.JSON.writeValueAsBytes(Map.of("message", message, "userId", user));
    var response =
        mvc.perform(
                post("/api/v1/agents/" + agent + "/invoke")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request))
            .andReturn()
            .getResponse();
    String raw = response.getContentAsString(StandardCharsets.UTF_8);
    return new Reply(response.getStatus(), LocalTestServers.JSON.readTree(raw), raw);
  }

  private JsonNode getJson(String url) throws Exception {
    return LocalTestServers.JSON.readTree(
        mvc.perform(get(url)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private String memoryText() throws IOException {
    return Files.exists(WS.memoryFile) ? Files.readString(WS.memoryFile) : "<no file>";
  }

  /** All text that sessions and both audit tables hold: used to prove nothing secret was stored. */
  private String everythingStored() {
    List<String> all = new ArrayList<>();
    all.addAll(E2eWorkspace.dump(jdbc, "SELECT messages_json FROM sessions"));
    all.addAll(E2eWorkspace.dump(jdbc, "SELECT error_message, provider FROM llm_calls"));
    all.addAll(
        E2eWorkspace.dump(
            jdbc, "SELECT error_message, error_category, tool_name FROM tool_invocations"));
    return String.join("\n", all);
  }

  // ---- scenario 1: weather ----------------------------------------------------------------

  @Test
  void theAnswerIsBuiltFromTheToolResultOfTheWeatherFixture() throws Exception {
    Reply reply = invoke("weather-agent", "wx", "查询上海今天的天气");

    assertEquals(200, reply.status());
    assertEquals("api:wx:weather-agent", reply.body().at("/data/sessionId").asText());
    String text = reply.body().at("/data/reply").asText();
    assertTrue(text.contains("Shanghai") && text.contains("21"), text);
  }

  @Test
  void theCountsAgreeBetweenTheModelServerTheFixtureAndSqlite() throws Exception {
    String memoryBefore = memoryText();
    MockMvcCounts counts = runOnce("weather-agent", "wx-counts", "查询上海今天的天气");

    assertEquals(2, MODEL.requestCount(), "the model server received exactly two requests");
    assertEquals(
        1, WEATHER.hits(), "the weather API was called exactly once (no double tool execution)");
    assertEquals(2, counts.llm());
    assertEquals(1, counts.tool());

    String session = "api:wx-counts:weather-agent";
    List<String> llm =
        E2eWorkspace.dump(
            jdbc,
            "SELECT provider, success, error_message FROM llm_calls WHERE session_id='"
                + session
                + "' ORDER BY id");
    assertEquals(
        List.of(
            "provider=fake-openai;success=1;error_message=null;",
            "provider=fake-openai;success=1;error_message=null;"),
        llm);
    List<String> tools =
        E2eWorkspace.dump(
            jdbc,
            "SELECT tool_name, success, error_category, error_message FROM tool_invocations WHERE session_id='"
                + session
                + "'");
    assertEquals(
        List.of("tool_name=http_get;success=1;error_category=null;error_message=null;"), tools);

    // The request the model saw first advertised only the Profile's tool; the second carried the
    // assistant's tool call and the tool's result, which is how the loop hands the observation
    // back.
    JsonNode first = MODEL.requests().get(0);
    assertEquals(1, first.get("tools").size());
    assertEquals("http_get", first.at("/tools/0/function/name").asText());
    JsonNode second = MODEL.requests().get(1);
    assertEquals("tool", lastRole(second));
    assertTrue(lastContent(second).contains("Shanghai"));

    // The stored transcript has the four steps of one ReAct round trip.
    String roles =
        jdbc.queryForObject(
            "SELECT messages_json FROM sessions WHERE session_id=?", String.class, session);
    JsonNode transcript = LocalTestServers.JSON.readTree(roles);
    List<String> order = new ArrayList<>();
    transcript.forEach(m -> order.add(m.get("role").asText()));
    assertEquals(List.of("USER", "ASSISTANT", "TOOL", "ASSISTANT"), order);

    assertEquals(memoryBefore, memoryText(), "a weather question must not touch long-term memory");
  }

  @Test
  void restAndTheConsoleReadTheSameRowsThatSqliteHolds() throws Exception {
    runOnce("weather-agent", "wx-rest", "查询上海今天的天气");
    String session = "api:wx-rest:weather-agent";

    JsonNode toolCalls = getJson("/api/v1/tool-calls?sessionId=" + session).get("data");
    assertEquals(
        E2eWorkspace.count(jdbc, "tool_invocations", session),
        toolCalls.size(),
        "REST /tool-calls shows every audit row");
    assertEquals("http_get", toolCalls.get(0).get("toolName").asText());

    JsonNode detail = getJson("/api/v1/sessions/" + session).get("data");
    assertEquals(4, detail.get("totalMessages").asInt());

    JsonNode list = getJson("/api/v1/sessions").get("data");
    boolean listed = false;
    for (JsonNode s : list) {
      listed |= session.equals(s.get("sessionId").asText());
    }
    assertTrue(listed);
    assertEquals(
        jdbc.queryForObject("SELECT COUNT(*) FROM sessions", Long.class),
        getJson("/api/v1/info").at("/data/sessions").asLong());
  }

  // ---- scenario 2: long-term memory --------------------------------------------------------

  @Test
  void rememberingAFactWritesRealMemoryAndANewSessionSeesIt() throws Exception {
    MockMvcCounts counts = runOnce("memory-agent", "mem-1", "记住：我喜欢喝美式咖啡");

    assertEquals(2, MODEL.requestCount());
    assertEquals(2, counts.llm());
    assertEquals(1, counts.tool());
    assertEquals(
        List.of("tool_name=save_memory;success=1;error_category=null;"),
        E2eWorkspace.dump(
            jdbc,
            "SELECT tool_name, success, error_category FROM tool_invocations WHERE session_id='api:mem-1:memory-agent'"));
    String file = memoryText();
    assertTrue(file.contains("我喜欢喝美式咖啡"));
    assertTrue(file.indexOf("## 核心记忆") < file.indexOf("我喜欢喝美式咖啡"), "stored under CORE memory");
    JsonNode memory = getJson("/api/v1/memory").get("data");
    assertEquals("我喜欢喝美式咖啡", memory.at("/core/0/content").asText());

    // A different session (a new user) asks something unrelated. The system prompt the model
    // receives already contains the fact, because CORE memory is read from the store every turn.
    MODEL.reset();
    runOnce("memory-agent", "mem-2-new-session", "你好");
    assertEquals(1, MODEL.requestCount());
    assertTrue(systemContent(MODEL.requests().get(0)).contains("我喜欢喝美式咖啡"));
    assertEquals(1, E2eWorkspace.count(jdbc, "llm_calls", "api:mem-2-new-session:memory-agent"));
    assertEquals(
        0, E2eWorkspace.count(jdbc, "tool_invocations", "api:mem-2-new-session:memory-agent"));
  }

  // ---- failure paths -----------------------------------------------------------------------

  @Test
  void aProviderThatCannotBeReachedIs503RecordedAndDoesNotLeakTheKey() throws Exception {
    Reply reply = invoke("broken-agent", "bad-provider", "hello");
    assertEquals(503, reply.status());
    assertEquals("Model provider unavailable", reply.body().get("message").asText());
    assertFalse(reply.raw().contains(E2eWorkspace.DUMMY_KEY));

    String session = "api:bad-provider:broken-agent";
    assertTrue(E2eWorkspace.count(jdbc, "llm_calls", session) >= 1);
    List<String> rows =
        E2eWorkspace.dump(
            jdbc,
            "SELECT provider, success, error_message FROM llm_calls WHERE session_id='"
                + session
                + "'");
    assertTrue(rows.stream().allMatch(r -> r.contains("provider=unreachable-openai;success=0;")));
    assertFalse(String.join("", rows).contains(E2eWorkspace.DUMMY_KEY));
    assertEquals(0, E2eWorkspace.count(jdbc, "tool_invocations", session));

    // The service is unharmed: the next, healthy request works.
    assertEquals("ok", getJson("/api/v1/health").at("/data/status").asText());
    assertEquals(200, invoke("memory-agent", "after-failure", "你好").status());
  }

  @Test
  void aSandboxRefusalIsAnAuditedDeniedCallNotASuccessAndLeaksNoPath() throws Exception {
    Reply reply = invoke("sandbox-agent", "deny", "请写文件");
    assertEquals(
        200, reply.status(), "the run itself completes: the model was told the call was refused");

    String session = "api:deny:sandbox-agent";
    assertEquals(
        List.of("tool_name=file_write;success=0;error_category=SandboxDenied;"),
        E2eWorkspace.dump(
            jdbc,
            "SELECT tool_name, success, error_category FROM tool_invocations WHERE session_id='"
                + session
                + "'"));
    String message =
        jdbc.queryForObject(
            "SELECT error_message FROM tool_invocations WHERE session_id=?", String.class, session);
    assertEquals("Path outside allowed WRITE roots", message);
    assertFalse(
        Files.exists(WS.forbidden.resolve("outside.txt")), "the refused write did not happen");
    String forbidden = E2eWorkspace.slash(WS.forbidden);
    String audit =
        String.join(
            " | ",
            E2eWorkspace.dump(jdbc, "SELECT error_message FROM llm_calls").toString(),
            E2eWorkspace.dump(
                    jdbc, "SELECT tool_name, error_category, error_message FROM tool_invocations")
                .toString());
    assertFalse(
        audit.contains(forbidden),
        "no path in the audit tables (arguments are never stored there)");
    // The session keeps the model's own tool-call arguments, but the observation the refusal
    // produced (the TOOL message) must not repeat or reveal any path.
    JsonNode transcript =
        LocalTestServers.JSON.readTree(
            jdbc.queryForObject(
                "SELECT messages_json FROM sessions WHERE session_id=?", String.class, session));
    for (JsonNode step : transcript) {
      if ("TOOL".equals(step.get("role").asText())) {
        assertFalse(step.get("content").asText().contains(forbidden));
      }
    }
    assertTrue(
        lastContent(MODEL.requests().getLast()).contains("SANDBOX_DENIED"),
        "the model was told, in the tool result");
  }

  @Test
  void aToolThatThrowsIsAFailedAuditRowWithoutItsMessageAndTheServiceSurvives() throws Exception {
    Reply reply = invoke("explode-agent", "boom", "请爆炸");
    assertEquals(200, reply.status());
    assertFalse(reply.raw().contains("secret-token-123"));

    String session = "api:boom:explode-agent";
    assertEquals(
        List.of(
            "tool_name=explode;success=0;error_category=ToolReturnedFailure;error_message=Tool error: IllegalStateException;"),
        E2eWorkspace.dump(
            jdbc,
            "SELECT tool_name, success, error_category, error_message FROM tool_invocations WHERE session_id='"
                + session
                + "'"));
    String stored = everythingStored();
    for (String secret : List.of("secret-token-123", "hunter2", "prod.db")) {
      assertFalse(stored.contains(secret), secret + " must not be stored");
    }
    assertEquals(2, E2eWorkspace.count(jdbc, "llm_calls", session));
    assertEquals("ok", getJson("/api/v1/health").at("/data/status").asText());
  }

  // ---- helpers ------------------------------------------------------------------------------

  private record MockMvcCounts(int llm, int tool) {}

  /** Runs one request from a clean counter state and returns the SQLite counts for its session. */
  private MockMvcCounts runOnce(String agent, String user, String message) throws Exception {
    MODEL.reset();
    WEATHER.reset();
    byte[] request =
        LocalTestServers.JSON.writeValueAsBytes(Map.of("message", message, "userId", user));
    int status =
        mvc.perform(
                post("/api/v1/agents/" + agent + "/invoke")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request))
            .andReturn()
            .getResponse()
            .getStatus();
    assertEquals(200, status);
    String session = "api:" + user + ":" + agent;
    return new MockMvcCounts(
        E2eWorkspace.count(jdbc, "llm_calls", session),
        E2eWorkspace.count(jdbc, "tool_invocations", session));
  }
}
