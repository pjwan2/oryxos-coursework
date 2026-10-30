package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Table-by-table reconciliation of the three demos: what the model server was asked, what the tools
 * did, what SQLite recorded and what the REST API shows must all agree, run by run.
 */
class AgentDemoAuditIT extends AbstractAgentDemoIT {
  @Test
  void eachRunIsAccountedForInEveryTableAndNoRunBorrowsAnotherRunsCalls() throws Exception {
    requirePython();
    record Run(String task, String session, int modelCalls, List<String> tools) {}
    List<Run> expected =
        List.of(
            new Run(
                WEATHER_TASK,
                "scheduler:scheduler:weather-daily",
                3,
                List.of("http_get:ok:null", "notify:ok:null")),
            new Run(
                TECH_TASK,
                "scheduler:scheduler:daily-tech-digest",
                4,
                List.of("file_read:ok:null", "http_get:ok:null", "notify:ok:null")),
            new Run(
                GITHUB_TASK,
                "scheduler:scheduler:github-daily",
                3,
                List.of("shell_exec:ok:null", "notify:ok:null")));
    int llmBefore = count("llm_calls");
    int toolBefore = count("tool_invocations");
    Set<String> ids = new HashSet<>();
    int modelCallsTotal = 0;
    int toolCallsTotal = 0;

    for (Run run : expected) {
      MODEL.reset();
      int llmRunBefore = count("llm_calls WHERE session_id = '" + run.session() + "'");
      JsonNode execution = DemoSupport.runNow(mvc, run.task());
      String id = execution.get("executionId").asText();

      assertTrue(ids.add(id), "every run has its own execution id");
      assertEquals(
          run.modelCalls(), MODEL.requestCount(), run.task() + ": requests the model server saw");
      assertEquals(
          run.modelCalls(),
          count("llm_calls WHERE session_id = '" + run.session() + "'") - llmRunBefore,
          run.task() + ": llm_calls rows");
      assertEquals(
          run.tools(), DemoSupport.toolRows(jdbc, id), run.task() + ": tool_invocations rows");
      assertEquals(
          1,
          count(
              "task_executions WHERE execution_id = '"
                  + id
                  + "' AND task_id = '"
                  + run.task()
                  + "'"),
          "one task_executions row");
      assertEquals(run.session(), execution.get("sessionId").asText());
      assertEquals(
          "COMPLETED",
          DemoSupport.outcome(mvc, run.task(), execution.get("id").asLong())
              .get("outcome")
              .asText());
      modelCallsTotal += run.modelCalls();
      toolCallsTotal += run.tools().size();
    }

    assertEquals(
        modelCallsTotal, count("llm_calls") - llmBefore, "no model call outside the three runs");
    assertEquals(
        toolCallsTotal,
        count("tool_invocations") - toolBefore,
        "no tool call outside the three runs");
    assertEquals(
        0,
        count("tool_invocations WHERE session_id LIKE 'scheduler:%' AND execution_id IS NULL"),
        "every scheduled tool call carries an execution id");
    assertEquals(
        0,
        count("llm_calls WHERE success = 0 AND session_id LIKE 'scheduler:scheduler:%'"),
        "no failed model call");
  }

  @Test
  void theRestAuditViewsShowTheSameRowsAsSqlite() throws Exception {
    JsonNode execution = DemoSupport.runNow(mvc, WEATHER_TASK);
    String session = execution.get("sessionId").asText();

    JsonNode calls =
        LocalTestServers.JSON
            .readTree(
                mvc.perform(
                        get("/api/v1/tool-calls").param("sessionId", session).param("limit", "200"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("data");
    List<String> viaRest = new ArrayList<>();
    for (JsonNode call : calls) {
      if (execution.get("executionId").asText().equals(call.path("executionId").asText())) {
        viaRest.add(
            call.get("toolName").asText()
                + ":"
                + (call.get("success").asBoolean() ? "ok" : "fail"));
      }
    }
    assertEquals(List.of("notify:ok", "http_get:ok"), viaRest, "newest first");
    assertEquals(
        List.of("http_get:ok:null", "notify:ok:null"),
        DemoSupport.toolRows(jdbc, execution.get("executionId").asText()));

    JsonNode sessions =
        LocalTestServers.JSON
            .readTree(
                mvc.perform(get("/api/v1/sessions")).andReturn().getResponse().getContentAsString())
            .get("data");
    List<String> ids = new ArrayList<>();
    sessions.forEach(s -> ids.add(s.get("sessionId").asText()));
    assertTrue(ids.contains(session));
    Integer rows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sessions WHERE session_id = ?", Integer.class, session);
    assertEquals(1, rows);
  }

  @Test
  void aFailedRunIsNeverCountedAsABusinessSuccessAnywhere() throws Exception {
    WEATHER.answerWith(500, "{}");
    GROUP.failWith(500);

    JsonNode execution = DemoSupport.runNow(mvc, WEATHER_TASK);

    assertTrue(execution.get("success").asBoolean(), "technically the run finished");
    JsonNode outcome = DemoSupport.outcome(mvc, WEATHER_TASK, execution.get("id").asLong());
    assertEquals("COMPLETED_WITH_TOOL_ISSUES", outcome.get("outcome").asText());
    assertEquals(
        2, outcome.get("toolFailed").asInt(), "the weather call and the notification both failed");
    assertEquals(0, outcome.get("toolSucceeded").asInt());
    assertTrue(GROUP.posts().isEmpty(), "nothing reached the group");
    assertFalse(outcome.get("outcome").asText().equals("COMPLETED"));
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT success FROM task_executions WHERE id = ?", execution.get("id").asLong());
    assertEquals(
        1,
        ((Number) row.get("success")).intValue(),
        "the stored technical status is unchanged (B3 semantics)");
  }
}
