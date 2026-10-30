package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.LocalTestServers.WeatherFixture;
import io.oryxos.cli.OryxCli;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The same two lesson-27 scenarios with the in-process deterministic {@code MockChatModel} (the
 * "mock" provider, no HTTP to a model at all), plus the check that the CLI, the REST API and the
 * dashboard read one persistence source.
 *
 * <p>The mock decides from the user's message ({@code tool:<name> <json>}); it can only request a
 * tool the Profile advertised. The tool executor, sandbox, memory store, SQLite audit and the
 * weather fixture are all real.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MockProviderFlowTest {
  private static final E2eWorkspace WS = new E2eWorkspace("oryxos-e2e-mock");
  private static final WeatherFixture WEATHER = new WeatherFixture();

  static {
    WS.profile("mock-weather", "mock", List.of("http_get"), E2eWorkspace.loopbackHttp(), null);
    WS.profile("mock-memory", "mock", List.of("save_memory", "recall_memory"), null, null);
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry registry) {
    WS.register(registry, "http://127.0.0.1:9");
  }

  @AfterAll
  static void stop() {
    WEATHER.close();
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    WEATHER.reset();
  }

  private JsonNode invoke(String agent, String user, String message) throws Exception {
    byte[] body =
        LocalTestServers.JSON.writeValueAsBytes(Map.of("message", message, "userId", user));
    var response =
        mvc.perform(
                post("/api/v1/agents/" + agent + "/invoke")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn()
            .getResponse();
    assertEquals(200, response.getStatus());
    return LocalTestServers.JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  private JsonNode getJson(String url) throws Exception {
    return LocalTestServers.JSON.readTree(
        mvc.perform(get(url)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private String memory() throws IOException {
    return Files.exists(WS.memoryFile) ? Files.readString(WS.memoryFile) : "<no file>";
  }

  @Test
  void weatherThroughTheMockProviderIsTwoModelCallsOneToolCallAndOneFixtureHit() throws Exception {
    String memoryBefore = memory();
    JsonNode reply =
        invoke("mock-weather", "w1", "tool:http_get {\"url\":\"" + WEATHER.url() + "\"}");

    String session = "api:w1:mock-weather";
    assertEquals(session, reply.at("/data/sessionId").asText());
    assertTrue(reply.at("/data/reply").asText().contains("Shanghai"), reply.toString());
    assertEquals(1, WEATHER.hits());
    // Two model calls: the one that asked for the tool, and the one that read its result.
    assertEquals(2, E2eWorkspace.count(jdbc, "llm_calls", session));
    assertEquals(
        List.of("provider=mock;success=1;", "provider=mock;success=1;"),
        E2eWorkspace.dump(
            jdbc,
            "SELECT provider, success FROM llm_calls WHERE session_id='"
                + session
                + "' ORDER BY id"));
    assertEquals(
        List.of("tool_name=http_get;success=1;error_category=null;"),
        E2eWorkspace.dump(
            jdbc,
            "SELECT tool_name, success, error_category FROM tool_invocations WHERE session_id='"
                + session
                + "'"));
    assertEquals(memoryBefore, memory(), "long-term memory untouched");
  }

  @Test
  void rememberingThroughTheMockProviderWritesMemoryAnotherSessionCanRecall() throws Exception {
    JsonNode saved = invoke("mock-memory", "m1", "tool:save_memory {\"content\":\"我喜欢喝美式咖啡\"}");

    String session = "api:m1:mock-memory";
    assertTrue(saved.at("/data/reply").asText().contains("ARCHIVAL"));
    assertEquals(2, E2eWorkspace.count(jdbc, "llm_calls", session));
    assertEquals(1, E2eWorkspace.count(jdbc, "tool_invocations", session));
    assertTrue(memory().contains("我喜欢喝美式咖啡"));

    // A brand-new session of another user finds it: memory is not part of any one conversation.
    JsonNode recalled =
        invoke("mock-memory", "m2-new-session", "tool:recall_memory {\"keyword\":\"美式\"}");
    assertTrue(recalled.at("/data/reply").asText().contains("我喜欢喝美式咖啡"), recalled.toString());
    String other = "api:m2-new-session:mock-memory";
    assertEquals(2, E2eWorkspace.count(jdbc, "llm_calls", other));
    assertEquals(1, E2eWorkspace.count(jdbc, "tool_invocations", other));
  }

  @Test
  void theCliTheRestApiAndTheDashboardReadTheSameDatabase() throws Exception {
    // The CLI starts its own Spring context (as a real `oryxos chat` would) on the same SQLite
    // file.
    Map<String, String> overrides = new LinkedHashMap<>(WS.properties("http://127.0.0.1:9"));
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    int exit =
        OryxCli.create(
                new BootCliRuntime(overrides),
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                new ByteArrayInputStream(new byte[0]))
            .execute(
                "chat",
                "mock-weather",
                "tool:http_get {\"url\":\"" + WEATHER.url() + "\"}",
                "--user",
                "cli-user");
    assertEquals(0, exit, err.toString());
    assertTrue(out.toString().contains("Shanghai"), out.toString());
    assertEquals(1, WEATHER.hits());

    String session = "cli:cli-user:mock-weather";
    assertEquals(2, E2eWorkspace.count(jdbc, "llm_calls", session));
    assertEquals(1, E2eWorkspace.count(jdbc, "tool_invocations", session));

    // REST sees exactly what the CLI wrote ...
    JsonNode detail = getJson("/api/v1/sessions/" + session).get("data");
    assertEquals("cli", detail.get("channel").asText());
    assertEquals(4, detail.get("totalMessages").asInt());
    JsonNode calls = getJson("/api/v1/tool-calls?sessionId=" + session).get("data");
    assertEquals(1, calls.size());
    assertEquals("http_get", calls.get(0).get("toolName").asText());
    // ... and the dashboard's list (the same endpoint it calls) contains the CLI's session.
    boolean listed = false;
    for (JsonNode s : getJson("/api/v1/sessions").get("data")) {
      listed |= session.equals(s.get("sessionId").asText());
    }
    assertTrue(listed);
    // The scheduler uses the same store too: its session id has the same shape (`scheduler:...`).
    assertEquals(
        jdbc.queryForObject("SELECT COUNT(*) FROM sessions", Long.class),
        getJson("/api/v1/info").at("/data/sessions").asLong());
  }
}
