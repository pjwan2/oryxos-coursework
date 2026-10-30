package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.memory.LongTermMemoryStore;
import io.oryxos.core.schedule.ScheduleService;
import io.oryxos.core.schedule.ScheduleUpdate;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Lesson 28 (B): the persisted state of a whole running system survives a restart unchanged.
 *
 * <p>The application context is started, used (sessions, long-term memory, a sandbox refusal, a
 * scheduled run, a disabled task), closed, and started again on the same files. Every table that
 * matters is dumped row by row before and after and compared, so "recovered" means "identical
 * rows", not "the application started". Run for both the Markdown and the SQLite memory backend.
 */
class FullStackRestartRecoveryTest {
  private static final String[] TABLES = {
    "SELECT * FROM sessions ORDER BY session_id",
    "SELECT * FROM llm_calls ORDER BY id",
    "SELECT * FROM tool_invocations ORDER BY id",
    "SELECT * FROM scheduled_tasks ORDER BY task_id",
    "SELECT * FROM task_executions ORDER BY id",
    "SELECT * FROM memory_entries ORDER BY id",
  };

  private static ConfigurableApplicationContext start(E2eWorkspace ws, String backend) {
    List<String> args = new ArrayList<>();
    args.add("--logging.level.root=ERROR");
    args.add("--oryxos.memory.backend=" + backend);
    ws.properties("http://127.0.0.1:9").forEach((key, value) -> args.add("--" + key + "=" + value));
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
    all.put(
        "memory file",
        List.of(Files.exists(ws.memoryFile) ? Files.readString(ws.memoryFile) : "<none>"));
    LongTermMemoryStore store = ctx.getBean(LongTermMemoryStore.class);
    all.put(
        "core memory via the interface", store.readCore().stream().map(e -> e.content()).toList());
    all.put(
        "archival memory via the interface",
        store.readArchival(100).stream().map(e -> e.content()).toList());
    return all;
  }

  @ParameterizedTest(name = "memory backend = {0}")
  @ValueSource(strings = {"markdown", "sqlite"})
  void sessionsMemorySchedulerAndBothAuditTablesComeBackRowForRow(String backend) throws Exception {
    E2eWorkspace ws = new E2eWorkspace("oryxos-e2e-restart-" + backend);
    ws.profile(
        "rec-agent",
        "mock",
        List.of("save_memory", "recall_memory", "file_read"),
        E2eWorkspace.files(ws.allowed),
        "schedules:\n  - id: tick\n    cron: \"0 0 9 1 1 *\"\n    zone: UTC\n    message: 'hello from the schedule'\n"
            + "  - id: tock\n    cron: \"0 0 10 1 1 *\"\n    zone: Asia/Shanghai\n    message: 'second task'\n");

    Map<String, List<String>> before;
    try (ConfigurableApplicationContext first = start(ws, backend)) {
      AgentService agent = first.getBean("springAiAgentService", AgentService.class);
      agent.process(
          "cli",
          "u1",
          "rec-agent",
          "tool:save_memory {\"content\":\"core fact\",\"scope\":\"CORE\"}");
      agent.process("cli", "u1", "rec-agent", "tool:save_memory {\"content\":\"archival fact\"}");
      agent.process(
          "cli",
          "u1",
          "rec-agent",
          "tool:file_read {\"path\":\""
              + E2eWorkspace.slash(ws.forbidden.resolve("x.txt"))
              + "\"}");
      ScheduleService schedules = first.getBean(ScheduleService.class);
      assertTrue(schedules.runNow("rec-agent.tick").success());
      schedules.update("rec-agent.tock", new ScheduleUpdate(false, null, null, null));
      before = snapshot(first, ws);
    }

    // The data we are about to compare is real and non-trivial.
    assertEquals(
        1,
        before.get(TABLES[0]).stream()
            .filter(r -> r.contains("session_id=cli:u1:rec-agent;"))
            .count());
    assertEquals(
        7,
        before.get(TABLES[1]).size(),
        "llm_calls: 3 tool chats x 2 calls + 1 scheduled echo x 1 call");
    assertEquals(3, before.get(TABLES[2]).size(), "tool_invocations: two saves and one refusal");
    assertTrue(
        before.get(TABLES[2]).stream().anyMatch(r -> r.contains("error_category=SandboxDenied")));
    assertEquals(List.of("core fact"), before.get("core memory via the interface"));
    assertEquals(List.of("archival fact"), before.get("archival memory via the interface"));

    try (ConfigurableApplicationContext second = start(ws, backend)) {
      Map<String, List<String>> after = snapshot(second, ws);
      assertEquals(before, after, "every persisted row is identical after the restart");

      // And the system carries on from where it was: same session, same scheduler counters.
      ScheduleService schedules = second.getBean(ScheduleService.class);
      var tock =
          schedules.list().stream()
              .filter(t -> t.taskId().equals("rec-agent.tock"))
              .findFirst()
              .orElseThrow();
      assertFalse(tock.enabled(), "the disabled switch survived");
      var tick =
          schedules.list().stream()
              .filter(t -> t.taskId().equals("rec-agent.tick"))
              .findFirst()
              .orElseThrow();
      assertEquals(1, tick.runCount());
      assertEquals("SUCCESS", tick.lastStatus());

      AgentService agent = second.getBean("springAiAgentService", AgentService.class);
      String recalled =
          agent.process("cli", "u1", "rec-agent", "tool:recall_memory {\"keyword\":\"archival\"}");
      assertTrue(recalled.contains("archival fact"));
      assertTrue(schedules.runNow("rec-agent.tick").success());
      JdbcTemplate jdbc = second.getBean(JdbcTemplate.class);
      assertEquals(
          2,
          jdbc.queryForObject(
              "SELECT run_count FROM scheduled_tasks WHERE task_id='rec-agent.tick'",
              Integer.class));
      List<String> llmAfterMore = E2eWorkspace.dump(jdbc, TABLES[1]);
      assertEquals(
          before.get(TABLES[1]),
          llmAfterMore.subList(0, before.get(TABLES[1]).size()),
          "old audit rows untouched, new ones appended");
      assertEquals(
          before.get(TABLES[1]).size() + 3,
          llmAfterMore.size(),
          "recall: 2 calls, scheduled echo: 1 call");
    }
  }
}
