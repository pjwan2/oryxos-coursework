package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The three demo agents are triggered by the REAL cron trigger of the running scheduler (not by
 * calling {@code runNow}), and the REST, CLI and cron paths are shown to be one code path.
 */
class AgentDemoSchedulerIT extends AbstractAgentDemoIT {
  private JsonNode json(String url) throws Exception {
    return LocalTestServers.JSON.readTree(
        mvc.perform(get(url)).andReturn().getResponse().getContentAsString());
  }

  /** The example with its cron moved to {@code seconds} from now (same zone), via the REST API. */
  private void fireInSeconds(String agent, int seconds) throws Exception {
    String md = Files.readString(WS.agents.resolve(agent).resolve("AGENT.md"));
    String zone =
        java.util.regex.Pattern.compile("zone: (\\S+)")
            .matcher(md)
            .results()
            .findFirst()
            .orElseThrow()
            .group(1);
    String edited =
        md.replaceFirst(
            "cron: \"[^\"]+\"", "cron: \"" + DemoSupport.cronInSeconds(zone, seconds) + "\"");
    var response =
        mvc.perform(
                put("/api/v1/agents/" + agent)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"agentMd\":" + DemoSupport.json(edited) + "}"))
            .andReturn()
            .getResponse();
    assertEquals(200, response.getStatus(), response.getContentAsString());
  }

  private static void eventually(String what, BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(250);
    }
    throw new AssertionError("Timed out waiting for: " + what);
  }

  private List<Map<String, Object>> cronRuns(String taskId) {
    return jdbc.queryForList(
        "SELECT id, execution_id, success, started_at FROM task_executions WHERE task_id = ?"
            + " AND trigger_type = 'cron' ORDER BY id",
        taskId);
  }

  @Test
  void everyDemoHasItsOwnScheduleInItsOwnTimeZone() throws Exception {
    Map<String, String> zones = new LinkedHashMap<>();
    for (JsonNode task : json("/api/v1/schedules").get("data")) {
      zones.put(
          task.get("taskId").asText(),
          task.get("zone").asText() + "/" + task.get("enabled").asText());
    }
    assertEquals("Asia/Shanghai/true", zones.get(WEATHER_TASK));
    assertEquals("Asia/Shanghai/true", zones.get(TECH_TASK));
    assertEquals("Asia/Shanghai/true", zones.get(GITHUB_TASK));
  }

  @Test
  void theRealCronTriggerRunsEachAgentOnceAndTheReportsArrive() throws Exception {
    requirePython();
    int runCountBefore = taskView(WEATHER_TASK).get("runCount").asInt();
    fireInSeconds("weather-daily", 6);
    fireInSeconds("daily-tech-digest", 8);
    fireInSeconds("github-daily", 10);

    eventually(
        "all three cron runs",
        () ->
            !cronRuns(WEATHER_TASK).isEmpty()
                && !cronRuns(TECH_TASK).isEmpty()
                && !cronRuns(GITHUB_TASK).isEmpty());
    eventually("three reports at the receiver", () -> GROUP.posts().size() >= 3);

    for (String task : List.of(WEATHER_TASK, TECH_TASK, GITHUB_TASK)) {
      List<Map<String, Object>> runs = cronRuns(task);
      assertEquals(1, runs.size(), task + " fired once");
      assertEquals(1, ((Number) runs.getFirst().get("success")).intValue(), task);
      assertNotNull(runs.getFirst().get("execution_id"));
      JsonNode outcome =
          DemoSupport.outcome(mvc, task, ((Number) runs.getFirst().get("id")).longValue());
      assertEquals("COMPLETED", outcome.get("outcome").asText(), task);
      assertEquals("execution_id", outcome.get("matchedBy").asText());
    }
    assertEquals(
        List.of("GitHub 日报", "今日天气与穿搭", "科技日报"),
        GROUP.posts().stream().map(DemoSupport.Receiver.Post::title).sorted().toList());
    assertEquals(
        List.of("http_get:ok:null", "notify:ok:null"),
        DemoSupport.toolRows(jdbc, (String) cronRuns(WEATHER_TASK).getFirst().get("execution_id")));
    assertEquals(
        List.of("shell_exec:ok:null", "notify:ok:null"),
        DemoSupport.toolRows(jdbc, (String) cronRuns(GITHUB_TASK).getFirst().get("execution_id")));
    JsonNode weatherTask = taskView(WEATHER_TASK);
    assertEquals(
        runCountBefore + 1, weatherTask.get("runCount").asInt(), "the cron run was counted once");
    assertEquals("SUCCESS", weatherTask.get("lastStatus").asText());
    assertNotNull(weatherTask.get("nextRunAt"), "the next day's run is already scheduled");
  }

  private JsonNode taskView(String taskId) throws Exception {
    for (JsonNode task : json("/api/v1/schedules").get("data")) {
      if (taskId.equals(task.get("taskId").asText())) {
        return task;
      }
    }
    throw new AssertionError("no task " + taskId);
  }

  @Test
  void restCliAndCronGiveTheModelTheSameInputsAndRunTheSameTools() throws Exception {
    // 1. REST manual run.
    JsonNode rest = DemoSupport.runNow(mvc, WEATHER_TASK);
    JsonNode restPrompt = MODEL.requests().getFirst();
    List<String> restTools = DemoSupport.toolRows(jdbc, rest.get("executionId").asText());
    MODEL.reset();
    GROUP.reset();

    // 2. CLI: a second application context on the same files and database.
    Map<String, String> cli =
        WS.properties(
            MODEL.baseUrl(),
            Map.of(
                "oryxos.sandbox.hosts", "127.0.0.1,api.open-meteo.com,hn.algolia.com",
                "oryxos.sandbox.read-roots",
                    DemoSupport.slash(WS.allowed) + "," + DemoSupport.slash(WS.agents),
                "oryxos.agents-watch", "false"));
    String message = "现在是早上，请查询今天的天气并发送穿搭建议。";
    BootCliRuntime runtime = new BootCliRuntime(cli);
    try {
      assertEquals("已发送", runtime.chat("local", "weather-daily", message));
    } finally {
      runtime.close();
    }
    JsonNode cliPrompt = MODEL.requests().getFirst();
    List<String> cliTools =
        jdbc.query(
            "SELECT tool_name, success, error_category FROM tool_invocations WHERE session_id ="
                + " 'cli:local:weather-daily' ORDER BY id",
            (rs, i) ->
                rs.getString(1)
                    + ":"
                    + (rs.getInt(2) == 1 ? "ok" : "fail")
                    + ":"
                    + rs.getString(3));
    assertEquals(1, GROUP.posts().size(), "the CLI run notified too");
    MODEL.reset();
    GROUP.reset();

    // 3. The cron trigger itself (it needs the scheduler to be started, which it is here).
    int cronRunsBefore = cronRuns(WEATHER_TASK).size();
    fireInSeconds("weather-daily", 5);
    eventually(
        "a new cron run of weather-daily",
        () -> cronRuns(WEATHER_TASK).size() > cronRunsBefore && !GROUP.posts().isEmpty());
    Map<String, Object> cron = cronRuns(WEATHER_TASK).getLast();
    JsonNode cronPrompt = MODEL.requests().getFirst();
    List<String> cronTools = DemoSupport.toolRows(jdbc, (String) cron.get("execution_id"));

    assertEquals(List.of("http_get:ok:null", "notify:ok:null"), restTools);
    assertEquals(restTools, cliTools);
    assertEquals(restTools, cronTools);
    String restSystem = LocalTestServers.systemContent(restPrompt);
    assertEquals(restSystem, LocalTestServers.systemContent(cliPrompt), "same prompt over the CLI");
    assertEquals(restSystem, LocalTestServers.systemContent(cronPrompt), "same prompt from cron");
    assertEquals(DemoSupport.offeredTools(restPrompt), DemoSupport.offeredTools(cronPrompt));
    assertTrue(Files.exists(WS.agents.resolve("weather-daily/AGENT.md")));
  }
}
