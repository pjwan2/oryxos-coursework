package io.oryxos;

import static io.oryxos.LocalTestServers.Decision.call;
import static io.oryxos.LocalTestServers.Decision.say;
import static io.oryxos.LocalTestServers.lastContent;
import static io.oryxos.LocalTestServers.lastRole;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.LocalTestServers.Decision;
import io.oryxos.LocalTestServers.FakeModelServer;
import io.oryxos.LocalTestServers.WeatherFixture;
import io.oryxos.scheduler.AgentScheduler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Lesson 28 (A and D): the scheduler through the whole real stack. A scheduled run is an ordinary
 * agent run: the REAL Spring AI client talks to a local model server (made slow on purpose so that
 * runs can really overlap), the real tools and sandbox act, SQLite records it, and the REST API
 * reports it.
 *
 * <p>Cron triggers are far in the future so nothing fires by itself; the cron path is driven by
 * calling {@code AgentScheduler.fire}, which is exactly what the trigger calls.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SchedulerStabilityE2ETest {
  private static final String FAR_CRON = "0 0 9 1 1 *";

  private static final E2eWorkspace WS = new E2eWorkspace("oryxos-e2e-sched");
  private static final WeatherFixture WEATHER = new WeatherFixture();
  private static final FakeModelServer MODEL =
      new FakeModelServer(SchedulerStabilityE2ETest::brain, 400);

  private static Decision brain(JsonNode request) {
    if ("tool".equals(lastRole(request))) {
      return say("done: " + lastContent(request));
    }
    String user = lastContent(request);
    if (user.contains("天气")) {
      return call("http_get", "{\"url\":\"" + WEATHER.url() + "\"}");
    }
    if (user.contains("写文件")) {
      String outside = E2eWorkspace.slash(WS.forbidden.resolve("scheduled.txt"));
      return call("file_write", "{\"path\":\"" + outside + "\",\"content\":\"x\"}");
    }
    return say("ok: " + user);
  }

  private static String schedule(String id, String cron, String zone, String message) {
    return "schedules:\n  - id: "
        + id
        + "\n    cron: \""
        + cron
        + "\"\n    zone: "
        + zone
        + "\n    message: '"
        + message
        + "'\n";
  }

  static {
    String fake = E2eWorkspace.FAKE_PROVIDER;
    WS.profile(
        "sched-chain",
        fake,
        List.of("http_get"),
        E2eWorkspace.loopbackHttp(),
        schedule("weather", FAR_CRON, "UTC", "查询上海今天的天气")
            + "  - id: shanghai\n    cron: \""
            + FAR_CRON
            + "\"\n    zone: Asia/Shanghai\n    message: '你好'\n");
    WS.profile("sched-other", fake, List.of(), null, schedule("ping", FAR_CRON, "UTC", "你好"));
    WS.profile(
        "sched-good",
        fake,
        List.of("http_get"),
        E2eWorkspace.loopbackHttp(),
        schedule("weather", FAR_CRON, "UTC", "查询上海今天的天气"));
    WS.profile(
        "sched-overlap",
        fake,
        List.of("http_get"),
        E2eWorkspace.loopbackHttp(),
        schedule("slow", FAR_CRON, "UTC", "查询上海今天的天气"));
    WS.profile("sched-switch", fake, List.of(), null, schedule("daily", FAR_CRON, "UTC", "你好"));
    WS.profile(
        "sched-deny",
        fake,
        List.of("file_write"),
        E2eWorkspace.files(WS.allowed),
        schedule("write", FAR_CRON, "UTC", "请写文件"));
    WS.profile(
        "sched-down",
        E2eWorkspace.UNREACHABLE_PROVIDER,
        List.of(),
        null,
        schedule("call", FAR_CRON, "UTC", "你好"));
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry registry) {
    WS.register(registry, MODEL.baseUrl());
  }

  @AfterAll
  static void stop() {
    MODEL.close();
    WEATHER.close();
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired AgentScheduler scheduler;

  @BeforeEach
  void reset() {
    MODEL.reset();
    WEATHER.reset();
  }

  private JsonNode json(String url, String method) throws Exception {
    var builder =
        switch (method) {
          case "POST" -> post(url);
          case "PUT" -> put(url);
          default -> get(url);
        };
    var response =
        mvc.perform(builder.contentType(MediaType.APPLICATION_JSON)).andReturn().getResponse();
    return LocalTestServers.JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
  }

  private int status(String url, String method) throws Exception {
    var builder = "POST".equals(method) ? post(url) : get(url);
    return mvc.perform(builder).andReturn().getResponse().getStatus();
  }

  private JsonNode task(String taskId) throws Exception {
    for (JsonNode t : json("/api/v1/schedules", "GET").get("data")) {
      if (taskId.equals(t.get("taskId").asText())) {
        return t;
      }
    }
    throw new AssertionError("not listed: " + taskId);
  }

  private String sql(String sql) {
    return jdbc.queryForObject(sql, String.class);
  }

  // ---- registration, time zone --------------------------------------------------------------

  @Test
  void everyScheduleOfEveryProfileIsRegisteredEnabledAndPersistedWithItsOwnTimeZone()
      throws Exception {
    List<String> ids = new ArrayList<>();
    json("/api/v1/schedules", "GET").get("data").forEach(t -> ids.add(t.get("taskId").asText()));
    assertEquals(
        List.of(
            "sched-chain.shanghai",
            "sched-chain.weather",
            "sched-deny.write",
            "sched-down.call",
            "sched-good.weather",
            "sched-other.ping",
            "sched-overlap.slow",
            "sched-switch.daily"),
        ids);
    assertEquals(
        8,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM scheduled_tasks WHERE enabled = 1", Integer.class));

    // Same wall-clock "09:00" in two zones is two different instants.
    Instant utc = Instant.parse(task("sched-chain.weather").get("nextRunAt").asText());
    Instant shanghai = Instant.parse(task("sched-chain.shanghai").get("nextRunAt").asText());
    assertEquals(9, utc.atZone(ZoneOffset.UTC).getHour());
    assertEquals(1, shanghai.atZone(ZoneOffset.UTC).getHour(), "09:00 Asia/Shanghai is 01:00 UTC");
    assertEquals("Asia/Shanghai", task("sched-chain.shanghai").get("zone").asText());
  }

  // ---- manual and cron triggers take the same road --------------------------------------------

  @Test
  void aManualRunAndACronRunBothGoThroughAgentServiceAndLeaveIdenticalRecords() throws Exception {
    String session = "scheduler:scheduler:sched-chain";

    JsonNode manual = json("/api/v1/schedules/sched-chain.weather/run", "POST").get("data");
    assertTrue(manual.get("success").asBoolean());
    assertEquals("manual", manual.get("trigger").asText());
    assertEquals(session, manual.get("sessionId").asText());
    assertEquals(2, MODEL.requestCount());
    assertEquals(1, WEATHER.hits());
    assertEquals(2, E2eWorkspace.count(jdbc, "llm_calls", session));
    assertEquals(1, E2eWorkspace.count(jdbc, "tool_invocations", session));

    scheduler.fire("sched-chain.weather");

    assertEquals(4, MODEL.requestCount(), "the cron path made the same two model calls");
    assertEquals(2, WEATHER.hits());
    assertEquals(4, E2eWorkspace.count(jdbc, "llm_calls", session));
    assertEquals(2, E2eWorkspace.count(jdbc, "tool_invocations", session));
    assertEquals(
        List.of(
            "trigger_type=manual;success=1;session_id=" + session + ";",
            "trigger_type=cron;success=1;session_id=" + session + ";"),
        E2eWorkspace.dump(
            jdbc,
            "SELECT trigger_type, success, session_id FROM task_executions WHERE task_id='sched-chain.weather' ORDER BY id"));
    assertEquals(
        "2", sql("SELECT run_count FROM scheduled_tasks WHERE task_id='sched-chain.weather'"));
    assertEquals(
        "SUCCESS",
        sql("SELECT last_status FROM scheduled_tasks WHERE task_id='sched-chain.weather'"));
    // Both runs appended to ONE scheduler session: 4 steps each.
    assertEquals(
        8,
        LocalTestServers.JSON
            .readTree(sql("SELECT messages_json FROM sessions WHERE session_id='" + session + "'"))
            .size());
  }

  // ---- no overlap ------------------------------------------------------------------------------

  @Test
  void sixSimultaneousRunsOfOneTaskExecuteExactlyOnce() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(6);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      results.add(
          pool.submit(
              () -> {
                go.await();
                return status("/api/v1/schedules/sched-overlap.slow/run", "POST");
              }));
    }
    go.countDown();
    int ok = 0;
    int conflict = 0;
    for (Future<Integer> result : results) {
      int code = result.get();
      if (code == 200) {
        ok++;
      } else if (code == 409) {
        conflict++;
      }
    }
    pool.shutdown();

    assertEquals(1, ok, "one run executed");
    assertEquals(5, conflict, "the other five were refused with 409, not queued or run twice");
    String session = "scheduler:scheduler:sched-overlap";
    assertEquals(2, MODEL.requestCount(), "the model was asked exactly once per ReAct step");
    assertEquals(1, WEATHER.hits());
    assertEquals(2, E2eWorkspace.count(jdbc, "llm_calls", session));
    assertEquals(
        "1", sql("SELECT run_count FROM scheduled_tasks WHERE task_id='sched-overlap.slow'"));
    assertEquals(
        "1", sql("SELECT COUNT(*) FROM task_executions WHERE task_id='sched-overlap.slow'"));
  }

  // ---- enabled switch
  // ----------------------------------------------------------------------------

  @Test
  void disablingIsPersistedCronThenSkipsAndAManualRunIsStillAllowed() throws Exception {
    String id = "sched-switch.daily";
    mvc.perform(
            put("/api/v1/schedules/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andReturn();
    assertEquals("0", sql("SELECT enabled FROM scheduled_tasks WHERE task_id='" + id + "'"));

    scheduler.fire(id);
    assertEquals(
        "0",
        sql("SELECT COUNT(*) FROM task_executions WHERE task_id='" + id + "'"),
        "cron skips a disabled task");
    assertEquals(0, MODEL.requestCount());

    // B3 semantics, kept: a person pressing "run" is allowed even when the task is disabled.
    JsonNode manual = json("/api/v1/schedules/" + id + "/run", "POST").get("data");
    assertTrue(manual.get("success").asBoolean());
    assertEquals("manual", manual.get("trigger").asText());
    assertEquals(
        "0",
        sql("SELECT enabled FROM scheduled_tasks WHERE task_id='" + id + "'"),
        "and it does not re-enable");
    assertEquals("1", sql("SELECT run_count FROM scheduled_tasks WHERE task_id='" + id + "'"));
  }

  // ---- technical success is not business success
  // ---------------------------------------------------

  @Test
  void aRunWhoseKeyToolWasRefusedIsTechnicallyASuccessButReportedAsCompletedWithToolIssues()
      throws Exception {
    JsonNode run = json("/api/v1/schedules/sched-deny.write/run", "POST").get("data");

    assertTrue(run.get("success").asBoolean(), "technical status: the agent run did not throw");
    assertFalse(
        java.nio.file.Files.exists(WS.forbidden.resolve("scheduled.txt")),
        "the write did not happen");
    assertEquals(
        List.of("tool_name=file_write;success=0;error_category=SandboxDenied;"),
        E2eWorkspace.dump(
            jdbc,
            "SELECT tool_name, success, error_category FROM tool_invocations WHERE session_id='scheduler:scheduler:sched-deny'"));
    assertEquals(
        "SUCCESS",
        task("sched-deny.write").get("lastStatus").asText(),
        "the stored status is unchanged (API compatible)");

    JsonNode outcome =
        json(
                "/api/v1/schedules/sched-deny.write/executions/"
                    + run.get("id").asLong()
                    + "/outcome",
                "GET")
            .get("data");
    assertEquals("SUCCESS", outcome.get("technicalStatus").asText());
    assertEquals("COMPLETED_WITH_TOOL_ISSUES", outcome.get("outcome").asText());
    assertEquals(1, outcome.get("toolDenied").asInt());
    assertEquals(0, outcome.get("toolSucceeded").asInt());

    // The weather task of the same stack really did complete.
    JsonNode good = json("/api/v1/schedules/sched-good.weather/run", "POST").get("data");
    JsonNode goodOutcome =
        json(
                "/api/v1/schedules/sched-good.weather/executions/"
                    + good.get("id").asLong()
                    + "/outcome",
                "GET")
            .get("data");
    assertEquals("COMPLETED", goodOutcome.get("outcome").asText());
    assertEquals(1, goodOutcome.get("toolSucceeded").asInt());
  }

  @Test
  void aRunThatCannotReachItsModelIsRecordedAsFailedWithoutLeakingAndTheSchedulerKeepsWorking()
      throws Exception {
    JsonNode run = json("/api/v1/schedules/sched-down.call/run", "POST").get("data");

    assertFalse(run.get("success").asBoolean());
    assertEquals("Agent run failed (ModelUnavailableException)", run.get("errorMessage").asText());
    assertFalse(run.toString().contains(E2eWorkspace.DUMMY_KEY));
    JsonNode outcome =
        json(
                "/api/v1/schedules/sched-down.call/executions/"
                    + run.get("id").asLong()
                    + "/outcome",
                "GET")
            .get("data");
    assertEquals("FAILED", outcome.get("outcome").asText());
    assertEquals("FAILED", task("sched-down.call").get("lastStatus").asText());

    // Another agent's task is unaffected by that failure.
    assertTrue(
        json("/api/v1/schedules/sched-other.ping/run", "POST").at("/data/success").asBoolean());
  }
}
