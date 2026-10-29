package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * The REST API against the whole real stack: Profile YAML, the one AgentService, the Mock model,
 * real tools and sandbox, a temporary SQLite file. Every API answer is compared with what is in the
 * database, so a controller that invents or drops data fails here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RestApiIntegrationTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ScheduleTestEnvironment ENV = environment();

  private static ScheduleTestEnvironment environment() {
    try {
      ScheduleTestEnvironment env =
          new ScheduleTestEnvironment(Files.createTempDirectory("oryxos-rest-it"));
      Files.writeString(
          env.profiles.resolve("api-demo.yaml"),
          "name: api-demo\n"
              + "description: REST integration profile\n"
              + "provider: {name: mock, model: m, temperature: 0.3}\n"
              + "tools: [file_read, file_write, save_memory, recall_memory]\n"
              + "settings:\n  max_iterations: 5\n  sandbox:\n    read_roots: [\""
              + ScheduleTestEnvironment.slash(env.allowed)
              + "\"]\n    write_roots: [\""
              + ScheduleTestEnvironment.slash(env.allowed)
              + "\"]\n");
      return env;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry registry) {
    registry.add("oryxos.profile-directory", () -> ScheduleTestEnvironment.slash(ENV.profiles));
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + ScheduleTestEnvironment.slash(ENV.database));
    registry.add("oryxos.memory.backend", () -> "sqlite");
    registry.add("oryxos.sandbox.read-roots", () -> ScheduleTestEnvironment.slash(ENV.allowed));
    registry.add("oryxos.sandbox.write-roots", () -> ScheduleTestEnvironment.slash(ENV.allowed));
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  private JsonNode data(RequestBuilder request) throws Exception {
    String body =
        mvc.perform(request)
            .andExpect(status().is2xxSuccessful())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JSON.readTree(body).get("data");
  }

  private JsonNode postJson(String url, String body) throws Exception {
    return data(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private static final String SESSION = "web:it-user:api-demo";

  // ---- sessions -------------------------------------------------------------------------

  @Test
  @Order(1)
  void aSessionCanBeCreatedChattedWithAndReadBackFromSqlite() throws Exception {
    JsonNode created =
        postJson("/api/v1/sessions", "{\"profile\":\"api-demo\",\"userId\":\"it-user\"}");
    assertEquals(SESSION, created.get("sessionId").asText());
    assertTrue(created.get("created").asBoolean());
    assertEquals(
        "[]",
        jdbc.queryForObject(
            "SELECT messages_json FROM sessions WHERE session_id = ?", String.class, SESSION));

    JsonNode reply =
        postJson("/api/v1/sessions/" + SESSION + "/messages", "{\"content\":\"hello\"}");
    assertEquals("Mock response: hello", reply.get("reply").asText());

    // One USER and one ASSISTANT message exist in the table and in the API, in the same order.
    JsonNode detail = data(get("/api/v1/sessions/" + SESSION));
    assertEquals(2, detail.get("totalMessages").asInt());
    JsonNode stored =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT messages_json FROM sessions WHERE session_id = ?", String.class, SESSION));
    assertEquals(stored.size(), detail.get("messages").size());
    for (int i = 0; i < stored.size(); i++) {
      assertEquals(
          stored.get(i).get("role").asText(), detail.get("messages").get(i).get("role").asText());
      assertEquals(
          stored.get(i).get("content").asText(),
          detail.get("messages").get(i).get("content").asText());
    }
  }

  @Test
  @Order(2)
  void createAgainIsIdempotentAndTheListMatchesTheTableNewestFirst() throws Exception {
    JsonNode again =
        postJson("/api/v1/sessions", "{\"profile\":\"api-demo\",\"userId\":\"it-user\"}");
    assertFalse(again.get("created").asBoolean());

    postJson("/api/v1/sessions", "{\"profile\":\"api-demo\",\"userId\":\"second\"}");
    postJson("/api/v1/sessions/web:second:api-demo/messages", "{\"content\":\"later message\"}");

    JsonNode list = data(get("/api/v1/sessions"));
    List<String> apiOrder = new ArrayList<>();
    list.forEach(n -> apiOrder.add(n.get("sessionId").asText()));
    List<String> sqlOrder =
        jdbc.queryForList(
            "SELECT session_id FROM sessions ORDER BY updated_at DESC, session_id ASC LIMIT 100",
            String.class);
    assertEquals(sqlOrder, apiOrder);
    assertEquals(
        "web:second:api-demo", apiOrder.get(0), "the most recently active session is first");
    assertEquals(
        jdbc.queryForObject("SELECT COUNT(*) FROM sessions", Integer.class), apiOrder.size());

    JsonNode first = list.get(0);
    assertEquals(2, first.get("messageCount").asInt());
    assertEquals("later message", first.get("lastUserMessage").asText());
    assertEquals("api-demo", first.get("profileName").asText());
    assertEquals("web", first.get("channel").asText());
  }

  @Test
  @Order(3)
  void unknownSessionsAndProfilesAre404AndBadInputIs400() throws Exception {
    mvc.perform(get("/api/v1/sessions/web:nobody:api-demo")).andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/sessions/web:nobody:api-demo/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"x\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profile\":\"no-such-profile\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/agents/no-such-agent/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"x\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/sessions/garbage")).andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/sessions/" + SESSION + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"" + "y".repeat(40_000) + "\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @Order(4)
  void deletingASessionRemovesTheRowButKeepsItsAudit() throws Exception {
    postJson("/api/v1/sessions", "{\"profile\":\"api-demo\",\"userId\":\"doomed\"}");
    String id = "web:doomed:api-demo";
    postJson(
        "/api/v1/sessions/" + id + "/messages",
        "{\"content\":\"tool:file_read {\\\"path\\\":\\\""
            + ScheduleTestEnvironment.slash(ENV.allowed.resolve("hello.txt"))
            + "\\\"}\"}");
    int auditBefore =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tool_invocations WHERE session_id = ?", Integer.class, id);
    assertEquals(1, auditBefore);

    mvc.perform(delete("/api/v1/sessions/" + id)).andExpect(status().isOk());

    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sessions WHERE session_id = ?", Integer.class, id));
    assertEquals(
        auditBefore,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tool_invocations WHERE session_id = ?", Integer.class, id));
    mvc.perform(get("/api/v1/sessions/" + id)).andExpect(status().isNotFound());
    mvc.perform(delete("/api/v1/sessions/" + id)).andExpect(status().isNotFound());
  }

  // ---- agents, tools, audit -------------------------------------------------------------

  @Test
  @Order(5)
  void invokeUsesTheApiChannelAndTheToolAuditMatchesTheTable() throws Exception {
    JsonNode ok =
        postJson(
            "/api/v1/agents/api-demo/invoke",
            "{\"message\":\"tool:file_read {\\\"path\\\":\\\""
                + ScheduleTestEnvironment.slash(ENV.allowed.resolve("hello.txt"))
                + "\\\"}\",\"userId\":\"caller\"}");
    assertEquals("api:caller:api-demo", ok.get("sessionId").asText());
    assertTrue(ok.get("reply").asText().contains("HELLO-FROM-ALLOWED"));

    // A write outside the declared roots is refused by the sandbox: the run itself still succeeds.
    JsonNode denied =
        postJson(
            "/api/v1/agents/api-demo/invoke",
            "{\"message\":\"tool:file_write {\\\"path\\\":\\\""
                + ScheduleTestEnvironment.slash(ENV.forbidden.resolve("nope.txt"))
                + "\\\",\\\"content\\\":\\\"x\\\"}\",\"userId\":\"caller\"}");
    assertTrue(denied.get("reply").asText().length() > 0);
    assertFalse(Files.exists(ENV.forbidden.resolve("nope.txt")));

    JsonNode calls = data(get("/api/v1/tool-calls?sessionId=api:caller:api-demo"));
    List<String> rows =
        jdbc.query(
            "SELECT tool_name || '|' || success || '|' || COALESCE(error_category,'') "
                + "FROM tool_invocations WHERE session_id = 'api:caller:api-demo' ORDER BY id DESC",
            (rs, n) -> rs.getString(1));
    List<String> api = new ArrayList<>();
    calls.forEach(
        c ->
            api.add(
                c.get("toolName").asText()
                    + "|"
                    + (c.get("success").asBoolean() ? 1 : 0)
                    + "|"
                    + (c.get("errorCategory").isNull() ? "" : c.get("errorCategory").asText())));
    assertEquals(rows, api);
    assertEquals(2, api.size());
    assertEquals("file_write|0|SandboxDenied", api.get(0));
    assertTrue(calls.get(0).get("errorMessage").asText().length() > 0);
    assertEquals("file_read|1|", api.get(1));

    // The same call window can be fetched by time, as the Scheduler page does.
    String since = calls.get(1).get("occurredAt").asText();
    assertEquals(
        2, data(get("/api/v1/tool-calls?sessionId=api:caller:api-demo&since=" + since)).size());
  }

  @Test
  @Order(6)
  void toolsAndProfilesDescribeExactlyWhatIsRegistered() throws Exception {
    JsonNode tools = data(get("/api/v1/tools"));
    List<String> names = new ArrayList<>();
    tools.forEach(
        t -> {
          names.add(t.get("name").asText());
          assertFalse(t.get("description").asText().isBlank());
          assertTrue(t.get("inputSchema").isObject(), "schema is returned as a JSON object");
        });
    assertTrue(names.containsAll(List.of("file_read", "file_write", "http_get", "save_memory")));
    assertEquals(names.stream().sorted().toList(), names, "tools are sorted by name");

    JsonNode profiles = data(get("/api/v1/profiles"));
    List<String> profileNames = new ArrayList<>();
    profiles.forEach(p -> profileNames.add(p.get("name").asText()));
    assertEquals(List.of("api-demo", "sched-broken", "sched-demo", "sched-other"), profileNames);
    JsonNode demo = profiles.get(0);
    assertEquals("mock", demo.get("provider").get("name").asText());
    assertEquals(4, demo.get("tools").size());
    assertTrue(demo.get("sandboxDeclared").asBoolean());
    assertEquals(4, profiles.get(2).get("scheduleCount").asInt());
  }

  // ---- memory ---------------------------------------------------------------------------

  @Test
  @Order(7)
  void memoryShowsWhatTheAgentSavedInTheConfiguredBackend() throws Exception {
    postJson(
        "/api/v1/agents/api-demo/invoke",
        "{\"message\":\"tool:save_memory {\\\"content\\\":\\\"prefers tea\\\",\\\"scope\\\":\\\"CORE\\\"}\"}");
    postJson(
        "/api/v1/agents/api-demo/invoke",
        "{\"message\":\"tool:save_memory {\\\"content\\\":\\\"visited Oslo\\\"}\"}");

    JsonNode memory = data(get("/api/v1/memory"));
    assertEquals("sqlite", memory.get("backend").asText());
    assertEquals("prefers tea", memory.get("core").get(0).get("content").asText());
    assertEquals("visited Oslo", memory.get("archival").get(0).get("content").asText());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM memory_entries WHERE scope='CORE' AND content='prefers tea'",
            Integer.class));
    assertEquals(
        jdbc.queryForObject("SELECT COUNT(*) FROM memory_entries", Integer.class),
        memory.get("core").size() + memory.get("archival").size());
  }

  // ---- system, headers, errors ----------------------------------------------------------

  @Test
  @Order(8)
  void healthAndInfoReflectTheRunningApplication() throws Exception {
    JsonNode health = data(get("/api/v1/health"));
    assertEquals("ok", health.get("status").asText());
    assertEquals("ok", health.get("checks").get("database").asText());

    JsonNode info = data(get("/api/v1/info"));
    assertEquals("oryxos", info.get("application").asText());
    assertEquals("sqlite", info.get("memoryBackend").asText());
    assertEquals(4, info.get("profiles").asInt());
    assertEquals(
        jdbc.queryForObject("SELECT COUNT(*) FROM sessions", Long.class),
        info.get("sessions").asLong());
    assertEquals(
        jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_tasks", Integer.class),
        info.get("schedules").asInt());
    assertNotEquals("", info.get("version").asText());
    String everything = info.toString();
    assertFalse(everything.contains("jdbc:"), "no database location in the info");
    assertFalse(everything.toLowerCase(java.util.Locale.ROOT).contains("api-key"));
  }

  @Test
  @Order(9)
  void everyResponseCarriesATraceIdAndErrorsUseTheEnvelope() throws Exception {
    mvc.perform(get("/api/v1/health")).andExpect(header().exists("X-Trace-Id"));
    mvc.perform(get("/api/v1/sessions/web:nobody:api-demo"))
        .andExpect(header().exists("X-Trace-Id"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.data").doesNotExist());
    mvc.perform(get("/api/v1/no-such-endpoint")).andExpect(status().isNotFound());
    mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
  }

  @Test
  @Order(10)
  void prometheusStaysAvailable() throws Exception {
    mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
  }

  @Test
  @Order(11)
  void schedulerRoutesStillWorkThroughTheSameApplication() throws Exception {
    JsonNode run = data(post("/api/v1/schedules/sched-demo.ok/run"));
    assertTrue(run.get("success").asBoolean());
    assertEquals("scheduler:scheduler:sched-demo", run.get("sessionId").asText());
    // Scheduler sessions show up in the ordinary session API.
    JsonNode detail = data(get("/api/v1/sessions/scheduler:scheduler:sched-demo"));
    assertEquals("scheduler", detail.get("channel").asText());
    assertTrue(detail.get("totalMessages").asInt() >= 2);
  }
}
