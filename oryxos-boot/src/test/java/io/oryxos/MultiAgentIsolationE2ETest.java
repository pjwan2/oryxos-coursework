package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.LocalTestServers.WeatherFixture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * Lesson 28 (C): two agents with different rights on one running system.
 *
 * <p>agent-files may use file tools inside one directory; agent-web may only call the loopback
 * weather API. Their tools, sandboxes, sessions and scheduled-task failures must not leak into one
 * another. The model is the in-process mock (deterministic, driven by {@code tool:<name> <json>}).
 *
 * <p>NOT isolated, and pinned below so nobody mistakes session isolation for it: long-term memory
 * is one global store shared by every agent that lists a memory tool. Per-agent memory is lesson
 * 30.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MultiAgentIsolationE2ETest {
  private static final E2eWorkspace WS = new E2eWorkspace("oryxos-e2e-iso");
  private static final WeatherFixture WEATHER = new WeatherFixture();
  private static final String B_ONLY_FILE_CONTENT = "B-ONLY-FILE-CONTENT";
  private static final String A_FILE_CONTENT = "A-OWN-FILE-CONTENT";

  static {
    WS.profile(
        "agent-files",
        "mock",
        List.of("file_read", "file_write", "save_memory"),
        E2eWorkspace.files(WS.allowed),
        null);
    WS.profile(
        "agent-web",
        "mock",
        List.of("http_get", "recall_memory"),
        E2eWorkspace.loopbackHttp(),
        null);
    WS.profile("iso-fine", "mock", List.of(), null, schedule("ok", "hello"));
    try {
      Files.writeString(WS.allowed.resolve("a.txt"), A_FILE_CONTENT);
      Files.writeString(WS.forbidden.resolve("b-secret.txt"), B_ONLY_FILE_CONTENT);
      // max_iterations is not a number, so every run of this agent fails inside the real loop.
      Files.writeString(
          WS.profiles.resolve("iso-broken.yaml"),
          "name: iso-broken\ndescription: always fails\nprovider: {name: mock, model: m, temperature: 0.0}\n"
              + "tools: []\nsettings:\n  max_iterations: \"not-a-number\"\n"
              + schedule("boom", "this run fails"));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String schedule(String id, String message) {
    return "schedules:\n  - id: "
        + id
        + "\n    cron: \"0 0 9 1 1 *\"\n    zone: UTC\n    message: '"
        + message
        + "'\n";
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

  private String reply(String agent, String user, String message) throws Exception {
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
    return LocalTestServers.JSON
        .readTree(response.getContentAsString(StandardCharsets.UTF_8))
        .at("/data/reply")
        .asText();
  }

  private String path(String name) {
    return E2eWorkspace.slash(WS.allowed.resolve(name));
  }

  private String sessionJson(String id) {
    return jdbc.queryForObject(
        "SELECT messages_json FROM sessions WHERE session_id=?", String.class, id);
  }

  @Test
  void eachAgentCanUseOnlyTheToolsItsProfileListsAndTheOtherAgentsToolsAreNotEvenOffered()
      throws Exception {
    assertTrue(
        reply("agent-files", "t1", "tool:file_read {\"path\":\"" + path("a.txt") + "\"}")
            .contains(A_FILE_CONTENT));
    assertTrue(
        reply("agent-web", "t1", "tool:http_get {\"url\":\"" + WEATHER.url() + "\"}")
            .contains("Shanghai"));

    assertEquals(
        "Tool not available: http_get",
        reply("agent-files", "t2", "tool:http_get {\"url\":\"" + WEATHER.url() + "\"}"));
    assertEquals(
        "Tool not available: file_read",
        reply("agent-web", "t2", "tool:file_read {\"path\":\"" + path("a.txt") + "\"}"));
    assertEquals(
        0,
        WEATHER.hits() - 1,
        "agent-files never reached the weather API (only agent-web's own call did)");
    assertEquals(
        0,
        E2eWorkspace.count(jdbc, "tool_invocations", "api:t2:agent-files"),
        "an unlisted tool is never executed, so there is nothing to audit");
    assertEquals(0, E2eWorkspace.count(jdbc, "tool_invocations", "api:t2:agent-web"));
  }

  @Test
  void agentFilesCannotReadAnotherAgentsResourceAndAgentWebCannotReachAnUnapprovedHost()
      throws Exception {
    String secretPath = E2eWorkspace.slash(WS.forbidden.resolve("b-secret.txt"));
    String denied = reply("agent-files", "s1", "tool:file_read {\"path\":\"" + secretPath + "\"}");
    assertTrue(denied.contains("SANDBOX_DENIED"));
    assertFalse(denied.contains(B_ONLY_FILE_CONTENT));
    assertEquals(
        List.of("tool_name=file_read;success=0;error_category=SandboxDenied;"),
        E2eWorkspace.dump(
            jdbc,
            "SELECT tool_name, success, error_category FROM tool_invocations WHERE session_id='api:s1:agent-files'"));

    // 127.0.0.2 is not the one host agent-web was granted, even though it is also loopback.
    String otherHost = WEATHER.url().replace("127.0.0.1", "127.0.0.2");
    assertTrue(
        reply("agent-web", "s1", "tool:http_get {\"url\":\"" + otherHost + "\"}")
            .contains("SANDBOX_DENIED"));
    assertEquals(0, WEATHER.hits(), "the fixture was never contacted");
    assertEquals(
        List.of("tool_name=http_get;success=0;error_category=SandboxDenied;"),
        E2eWorkspace.dump(
            jdbc,
            "SELECT tool_name, success, error_category FROM tool_invocations WHERE session_id='api:s1:agent-web'"));
  }

  @Test
  void theSameUserTalkingToBothAgentsGetsTwoUnrelatedSessions() throws Exception {
    reply("agent-files", "shared-user", "tool:file_read {\"path\":\"" + path("a.txt") + "\"}");
    reply("agent-web", "shared-user", "tool:http_get {\"url\":\"" + WEATHER.url() + "\"}");

    String files = sessionJson("api:shared-user:agent-files");
    String web = sessionJson("api:shared-user:agent-web");
    assertTrue(files.contains(A_FILE_CONTENT));
    assertFalse(files.contains("Shanghai"), "agent-web's data is not in agent-files' session");
    assertTrue(web.contains("Shanghai"));
    assertFalse(web.contains(A_FILE_CONTENT), "agent-files' data is not in agent-web's session");

    JsonNode listing = LocalLists.sessions(mvc);
    assertEquals("agent-files", LocalLists.profileOf(listing, "api:shared-user:agent-files"));
    assertEquals("agent-web", LocalLists.profileOf(listing, "api:shared-user:agent-web"));
  }

  /**
   * Characterisation test, not a goal: documents that memory is GLOBAL today. When per-agent memory
   * is built (lesson 30) this test is expected to change, deliberately.
   */
  @Test
  void longTermMemoryIsStillGlobalSoOneAgentCanReadWhatAnotherSaved_notIsolated() throws Exception {
    reply("agent-files", "mem", "tool:save_memory {\"content\":\"alpha-private-fact\"}");

    String recalled = reply("agent-web", "mem", "tool:recall_memory {\"keyword\":\"alpha\"}");
    assertTrue(
        recalled.contains("alpha-private-fact"),
        "per-agent memory isolation is NOT implemented: agent-web read agent-files' memory");
  }

  @Test
  void aFailingScheduledAgentDoesNotStopAnotherAgentsScheduledTask() throws Exception {
    JsonNode broken = runTask("iso-broken.boom");
    assertFalse(broken.get("success").asBoolean());
    assertEquals(
        "Agent run failed (IllegalArgumentException)", broken.get("errorMessage").asText());

    JsonNode fine = runTask("iso-fine.ok");
    assertTrue(fine.get("success").asBoolean());

    assertEquals(
        List.of("task_id=iso-broken.boom;success=0;", "task_id=iso-fine.ok;success=1;"),
        E2eWorkspace.dump(jdbc, "SELECT task_id, success FROM task_executions ORDER BY task_id"));
    assertEquals(1, E2eWorkspace.count(jdbc, "sessions", "scheduler:scheduler:iso-fine"));
    assertEquals(
        1,
        E2eWorkspace.count(jdbc, "sessions", "scheduler:scheduler:iso-broken"),
        "even a failed run saves its partial session, separately");
  }

  private JsonNode runTask(String taskId) throws Exception {
    var response =
        mvc.perform(post("/api/v1/schedules/" + taskId + "/run")).andReturn().getResponse();
    assertEquals(200, response.getStatus());
    return LocalTestServers.JSON
        .readTree(response.getContentAsString(StandardCharsets.UTF_8))
        .get("data");
  }

  /** Small helpers over the session list endpoint. */
  private static final class LocalLists {
    static JsonNode sessions(MockMvc mvc) throws Exception {
      return LocalTestServers.JSON
          .readTree(
              mvc.perform(get("/api/v1/sessions"))
                  .andReturn()
                  .getResponse()
                  .getContentAsString(StandardCharsets.UTF_8))
          .get("data");
    }

    static String profileOf(JsonNode list, String sessionId) {
      for (JsonNode s : list) {
        if (sessionId.equals(s.get("sessionId").asText())) {
          return s.get("profileName").asText();
        }
      }
      return null;
    }
  }
}
