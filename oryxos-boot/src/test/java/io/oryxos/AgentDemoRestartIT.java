package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.oryxos.DemoSupport.GithubApi;
import io.oryxos.DemoSupport.NewsApi;
import io.oryxos.DemoSupport.Receiver;
import io.oryxos.DemoSupport.WeatherApi;
import io.oryxos.LocalTestServers.FakeModelServer;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.memory.AgentMemories;
import io.oryxos.core.profile.AgentManagement;
import io.oryxos.core.schedule.ScheduleService;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Stop the application and start it again on the same files and database: the three demo agents,
 * their schedules, their private memories and every audit row are exactly as they were, and they
 * still run.
 */
class AgentDemoRestartIT {
  private static final String[] TABLES = {
    "SELECT * FROM sessions ORDER BY session_id",
    "SELECT * FROM llm_calls ORDER BY id",
    "SELECT * FROM tool_invocations ORDER BY id",
    "SELECT * FROM scheduled_tasks ORDER BY task_id",
    "SELECT * FROM task_executions ORDER BY id",
  };

  private static ConfigurableApplicationContext start(
      E2eWorkspace ws, String modelUrl, String python) {
    List<String> args = new ArrayList<>();
    args.add("--logging.level.root=ERROR");
    ws.properties(
            modelUrl,
            Map.of(
                "oryxos.sandbox.hosts",
                "127.0.0.1,api.open-meteo.com,hn.algolia.com",
                "oryxos.sandbox.read-roots",
                DemoSupport.slash(ws.allowed) + "," + DemoSupport.slash(ws.agents),
                "oryxos.sandbox.commands",
                python,
                "oryxos.agents-watch",
                "false"))
        .forEach((key, value) -> args.add("--" + key + "=" + value));
    return new SpringApplicationBuilder(OryxOsApplication.class)
        .web(WebApplicationType.NONE)
        .bannerMode(org.springframework.boot.Banner.Mode.OFF)
        .run(args.toArray(String[]::new));
  }

  private static Map<String, List<String>> snapshot(
      ConfigurableApplicationContext ctx, E2eWorkspace ws) throws IOException {
    JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
    Map<String, List<String>> all = new LinkedHashMap<>();
    for (String sql : TABLES) {
      all.put(sql, E2eWorkspace.dump(jdbc, sql));
    }
    for (String agent : List.of("weather-daily", "daily-tech-digest", "github-daily")) {
      for (String file : List.of("AGENT.md", "MEMORY.md")) {
        var path = ws.agents.resolve(agent).resolve(file);
        all.put(
            agent + "/" + file, List.of(Files.exists(path) ? Files.readString(path) : "<none>"));
      }
    }
    return all;
  }

  @Test
  void configurationMemoryScheduleAndAuditSurviveARestartAndTheAgentsStillRun() throws Exception {
    String python = GitHubTrendingScriptTest.findPython();
    assumeTrue(python != null, "no Python interpreter on PATH");
    E2eWorkspace ws = new E2eWorkspace("oryxos-c5-restart");
    try (WeatherApi weather = new WeatherApi();
        NewsApi news = new NewsApi();
        GithubApi github = new GithubApi();
        Receiver group = new Receiver();
        FakeModelServer model = new FakeModelServer(DemoSupport::brain, 0)) {
      DemoSupport.installWeather(ws, group.url(), weather.forecastUrl(), "0 0 8 1 1 *");
      DemoSupport.installTech(ws, group.url(), news.searchUrl(), "0 0 9 1 1 *");
      DemoSupport.installGithub(ws, group.url(), github.baseUrl(), python, "0 30 9 1 1 *");

      Map<String, List<String>> before;
      try (ConfigurableApplicationContext first = start(ws, model.baseUrl(), python)) {
        AgentService agent = first.getBean("springAiAgentService", AgentService.class);
        agent.process("api", "owner", "daily-tech-digest", "记住：优先关注AI和芯片");
        agent.process("api", "owner", "github-daily", "记住：优先关注 RAG 相关项目");
        ScheduleService schedules = first.getBean(ScheduleService.class);
        for (String task :
            List.of("weather-daily.morning", "daily-tech-digest.morning", "github-daily.morning")) {
          assertTrue(schedules.runNow(task).success(), task);
        }
        before = snapshot(first, ws);
      }
      assertEquals(3, group.posts().size());
      assertTrue(before.get("daily-tech-digest/MEMORY.md").getFirst().contains("AI和芯片"));
      assertEquals("<none>", before.get("weather-daily/MEMORY.md").getFirst());
      assertEquals(3, before.get(TABLES[4]).size(), "three scheduled runs");
      List<String> toolRows = before.get(TABLES[2]);
      assertEquals(9, toolRows.size(), "7 tool calls of the three runs + 2 memory saves");
      assertEquals(
          2,
          toolRows.stream().filter(r -> r.contains("execution_id=null;")).count(),
          "only the two chat messages have no execution id");

      try (ConfigurableApplicationContext second = start(ws, model.baseUrl(), python)) {
        assertEquals(before, snapshot(second, ws), "every persisted row and file is identical");

        AgentManagement agents = second.getBean(AgentManagement.class);
        assertEquals(
            List.of("daily-tech-digest", "github-daily", "weather-daily"),
            agents.list().stream().map(v -> v.name()).toList());
        ScheduleService schedules = second.getBean(ScheduleService.class);
        var tick =
            schedules.list().stream()
                .filter(t -> t.taskId().equals("weather-daily.morning"))
                .findFirst()
                .orElseThrow();
        assertTrue(tick.enabled());
        assertEquals(1, tick.runCount(), "the run counter survived");
        assertEquals("Asia/Shanghai", tick.zone());

        AgentMemories memories = second.getBean(AgentMemories.class);
        assertEquals(
            List.of("优先关注AI和芯片"),
            memories.forAgent("daily-tech-digest").readCore().stream()
                .map(e -> e.content())
                .toList());
        assertEquals(
            List.of("优先关注 RAG 相关项目"),
            memories.forAgent("github-daily").readCore().stream().map(e -> e.content()).toList());

        group.reset();
        assertTrue(
            schedules.runNow("github-daily.morning").success(), "still runs after the restart");
        assertEquals(1, group.posts().size());
        assertFalse(group.posts().getFirst().text().isBlank());
      }
    }
  }
}
