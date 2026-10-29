package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.scheduler.AgentScheduler;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
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

/**
 * Definition -> registration -> execution -> persistence -> query -> disable, through the real
 * stack: Profile YAML, real Spring context, Mock provider, real tools and sandbox, a temporary
 * SQLite file and the HTTP API. No network, no API key, no webhook.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScheduledTaskE2ETest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ScheduleTestEnvironment ENV = environment();

  private static ScheduleTestEnvironment environment() {
    try {
      return new ScheduleTestEnvironment(Files.createTempDirectory("oryxos-sched-e2e"));
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
    registry.add(
        "oryxos.memory.markdown-path",
        () -> ScheduleTestEnvironment.slash(ENV.root.resolve("MEMORY.md")));
    registry.add("oryxos.sandbox.read-roots", () -> ScheduleTestEnvironment.slash(ENV.allowed));
    registry.add("oryxos.sandbox.write-roots", () -> ScheduleTestEnvironment.slash(ENV.allowed));
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired AgentScheduler scheduler;

  private JsonNode call(org.springframework.test.web.servlet.RequestBuilder request)
      throws Exception {
    String body =
        mvc.perform(request)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JSON.readTree(body).get("data");
  }

  private JsonNode task(String taskId) throws Exception {
    for (JsonNode node : call(get("/api/v1/schedules"))) {
      if (node.get("taskId").asText().equals(taskId)) {
        return node;
      }
    }
    throw new AssertionError("not listed: " + taskId);
  }

  @Test
  @Order(1)
  void everyScheduleOfEveryProfileIsRegisteredAndPersistedEnabledWithZeroRuns() throws Exception {
    JsonNode all = call(get("/api/v1/schedules"));
    List<String> ids = new java.util.ArrayList<>();
    all.forEach(n -> ids.add(n.get("taskId").asText()));
    assertEquals(
        List.of(
            "sched-broken.boom",
            "sched-demo.denied-http",
            "sched-demo.denied-write",
            "sched-demo.ok",
            "sched-demo.read",
            "sched-other.ok"),
        ids);

    for (JsonNode node : all) {
      assertTrue(node.get("enabled").asBoolean());
      assertFalse(node.get("running").asBoolean());
      assertEquals(0, node.get("runCount").asInt());
      assertNull(node.get("lastStatus").isNull() ? null : node.get("lastStatus").asText());
      assertFalse(node.get("nextRunAt").isNull(), "next run time is computed at registration");
    }
    assertEquals("Asia/Shanghai", task("sched-demo.ok").get("zone").asText());
    assertEquals("America/New_York", task("sched-other.ok").get("zone").asText());
    assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_tasks", Integer.class));
  }

  @Test
  @Order(2)
  void aSuccessfulRunIsExecutedByTheAgentAndRecordedEverywhere() throws Exception {
    JsonNode run = call(post("/api/v1/schedules/sched-demo.ok/run"));
    assertTrue(run.get("success").asBoolean());
    assertEquals("manual", run.get("trigger").asText());
    assertEquals("scheduler:scheduler:sched-demo", run.get("sessionId").asText());
    assertTrue(run.get("id").asLong() > 0);

    JsonNode task = task("sched-demo.ok");
    assertEquals(1, task.get("runCount").asInt());
    assertEquals("SUCCESS", task.get("lastStatus").asText());
    assertNotNull(task.get("lastRunAt").asText());

    JsonNode history = call(get("/api/v1/schedules/sched-demo.ok/executions"));
    assertEquals(1, history.size());
    assertEquals(run.get("id").asLong(), history.get(0).get("id").asLong());

    // The scheduler used the ordinary session machinery: one row, key built by SessionIds.
    String transcript =
        jdbc.queryForObject(
            "SELECT messages_json FROM sessions WHERE session_id = 'scheduler:scheduler:sched-demo'",
            String.class);
    assertTrue(transcript.contains("hello from the schedule"), transcript);
    assertTrue(transcript.contains("Mock response: hello from the schedule"), transcript);
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT channel, user_id, profile_name FROM sessions WHERE session_id ="
                + " 'scheduler:scheduler:sched-demo'");
    assertEquals("scheduler", row.get("channel"));
    assertEquals("scheduler", row.get("user_id"));
    assertEquals("sched-demo", row.get("profile_name"));
  }

  @Test
  @Order(3)
  void aScheduledToolCallUsesTheProfilesSandboxAndIsAudited() throws Exception {
    JsonNode read = call(post("/api/v1/schedules/sched-demo.read/run"));
    assertTrue(read.get("success").asBoolean());
    assertTrue(
        jdbc.queryForObject(
                "SELECT messages_json FROM sessions WHERE session_id ="
                    + " 'scheduler:scheduler:sched-demo'",
                String.class)
            .contains("HELLO-FROM-ALLOWED"));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tool_invocations WHERE session_id ="
                + " 'scheduler:scheduler:sched-demo' AND tool_name = 'file_read' AND success = 1",
            Integer.class));
  }

  @Test
  @Order(4)
  void aSandboxDeniedToolCallHasNoSideEffectAndLeavesAReadableAuditReason() throws Exception {
    call(post("/api/v1/schedules/sched-demo.denied-write/run"));
    call(post("/api/v1/schedules/sched-demo.denied-http/run"));

    assertFalse(
        Files.exists(ENV.forbidden.resolve("created-by-schedule.txt")),
        "a denied write must not create the file");
    List<Map<String, Object>> denied =
        jdbc.queryForList(
            "SELECT tool_name, success, error_category, error_message FROM tool_invocations"
                + " WHERE session_id = 'scheduler:scheduler:sched-demo'"
                + " AND error_category = 'SandboxDenied' ORDER BY id");
    assertEquals(2, denied.size(), denied.toString());
    assertEquals("file_write", denied.get(0).get("tool_name"));
    assertEquals("http_get", denied.get(1).get("tool_name"));
    for (Map<String, Object> row : denied) {
      assertEquals(0, ((Number) row.get("success")).intValue());
      String reason = (String) row.get("error_message");
      assertNotNull(reason, "the audit row carries a reason now");
      assertFalse(reason.isBlank());
      assertFalse(reason.contains("SECRETKEY"), reason);
      assertFalse(reason.contains("hooks.example.org/x"), reason);
    }
  }

  @Test
  @Order(5)
  void aFailingRunIsRecordedAsFailedWithoutTheRawMessageAndTheSchedulerKeepsWorking()
      throws Exception {
    JsonNode failed = call(post("/api/v1/schedules/sched-broken.boom/run"));
    assertFalse(failed.get("success").asBoolean());
    assertEquals(
        "Agent run failed (IllegalArgumentException)", failed.get("errorMessage").asText());

    JsonNode task = task("sched-broken.boom");
    assertEquals("FAILED", task.get("lastStatus").asText());
    assertEquals(1, task.get("runCount").asInt());
    assertFalse(task.get("running").asBoolean(), "the lock was released");

    // The failure of one agent leaves the others, and the same agent's next run, untouched.
    JsonNode other = call(post("/api/v1/schedules/sched-other.ok/run"));
    assertTrue(other.get("success").asBoolean());
    assertEquals("scheduler:scheduler:sched-other", other.get("sessionId").asText());
    JsonNode again = call(post("/api/v1/schedules/sched-broken.boom/run"));
    assertFalse(again.get("success").asBoolean());
    assertEquals(2, task("sched-broken.boom").get("runCount").asInt());
    assertEquals("SUCCESS", task("sched-other.ok").get("lastStatus").asText());
    assertEquals("SUCCESS", task("sched-demo.ok").get("lastStatus").asText());
  }

  @Test
  @Order(6)
  void executionsAreQueryableNewestFirstAndLimited() throws Exception {
    call(post("/api/v1/schedules/sched-demo.ok/run"));
    call(post("/api/v1/schedules/sched-demo.ok/run"));
    JsonNode history = call(get("/api/v1/schedules/sched-demo.ok/executions?limit=2"));
    assertEquals(2, history.size());
    assertTrue(history.get(0).get("id").asLong() > history.get(1).get("id").asLong());
    assertEquals(3, task("sched-demo.ok").get("runCount").asInt());
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM task_executions WHERE task_id = 'sched-demo.ok'", Integer.class));
  }

  @Test
  @Order(7)
  void disablingStopsCronExecutionAndWritesNoHistoryButIsPersisted() throws Exception {
    JsonNode disabled =
        call(
            put("/api/v1/schedules/sched-demo.ok")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"));
    assertFalse(disabled.get("enabled").asBoolean());
    assertTrue(disabled.get("nextRunAt").isNull());

    int before = task("sched-demo.ok").get("runCount").asInt();
    scheduler.fire("sched-demo.ok"); // what the cron trigger calls
    scheduler.fire("sched-demo.ok");
    assertEquals(before, task("sched-demo.ok").get("runCount").asInt());
    assertEquals(
        before,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM task_executions WHERE task_id = 'sched-demo.ok'", Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT enabled FROM scheduled_tasks WHERE task_id = 'sched-demo.ok'", Integer.class));
    assertTrue(task("sched-other.ok").get("enabled").asBoolean(), "other tasks unaffected");

    // A cron firing of an enabled task still works end to end.
    int otherBefore = task("sched-other.ok").get("runCount").asInt();
    scheduler.fire("sched-other.ok");
    assertEquals(otherBefore + 1, task("sched-other.ok").get("runCount").asInt());
  }

  @Test
  @Order(8)
  void updatingTheCronAndZoneIsPersistedAndRecomputesTheNextRun() throws Exception {
    JsonNode before = task("sched-other.ok");
    JsonNode updated =
        call(
            put("/api/v1/schedules/sched-other.ok")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cron\":\"0 0 5 1 1 *\",\"zone\":\"UTC\"}"));
    assertEquals("0 0 5 1 1 *", updated.get("cron").asText());
    assertEquals("UTC", updated.get("zone").asText());
    assertTrue(updated.get("nextRunAt").asText().endsWith("T05:00:00Z"), updated.toString());
    assertEquals(before.get("runCount").asInt(), updated.get("runCount").asInt());
    assertEquals(
        "0 0 5 1 1 *",
        jdbc.queryForObject(
            "SELECT cron FROM scheduled_tasks WHERE task_id = 'sched-other.ok'", String.class));
  }

  @Test
  @Order(9)
  void invalidInputAndUnknownIdsAreRejectedWithoutChangingAnything() throws Exception {
    mvc.perform(
            put("/api/v1/schedules/sched-other.ok")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cron\":\"every day\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            put("/api/v1/schedules/sched-other.ok")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"zone\":\"Mars/Base\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/schedules/nobody.nothing/run"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Unknown schedule"));
    mvc.perform(get("/api/v1/schedules/nobody.nothing/executions"))
        .andExpect(status().isNotFound());
    assertEquals("0 0 5 1 1 *", task("sched-other.ok").get("cron").asText());
  }

  @Test
  @Order(10)
  void managementWritesFromARemoteAddressAreRefused() throws Exception {
    mvc.perform(
            post("/api/v1/schedules/sched-demo.ok/run")
                .with(
                    request -> {
                      request.setRemoteAddr("198.51.100.20");
                      return request;
                    }))
        .andExpect(status().isForbidden());
    assertEquals(3, task("sched-demo.ok").get("runCount").asInt());
  }
}
