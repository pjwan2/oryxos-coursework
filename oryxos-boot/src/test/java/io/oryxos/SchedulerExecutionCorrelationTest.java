package io.oryxos;

import static io.oryxos.LocalTestServers.Decision.call;
import static io.oryxos.LocalTestServers.Decision.say;
import static io.oryxos.LocalTestServers.lastContent;
import static io.oryxos.LocalTestServers.lastRole;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.LocalTestServers.Decision;
import io.oryxos.LocalTestServers.FakeModelServer;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.ToolCallHistory;
import io.oryxos.core.schedule.ScheduleService;
import io.oryxos.core.schedule.TaskExecution;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The audit ties every tool call to the run that made it. Two tasks of one agent share the agent's
 * scheduler session and (because runs of one session are serialized) their time windows overlap;
 * the old "same session, same time window" lookup therefore mixed their calls up. Matching by
 * execution id does not. Real SQLite, real tools, real sandbox, real REST.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SchedulerExecutionCorrelationTest {
  private static final String FAR_CRON = "0 0 9 1 1 *";
  private static final E2eWorkspace WS = new E2eWorkspace("oryxos-e2e-correlation");
  private static final FakeModelServer MODEL =
      new FakeModelServer(SchedulerExecutionCorrelationTest::brain, 250);

  private static Decision brain(JsonNode request) {
    if ("tool".equals(lastRole(request))) {
      return say("done: " + lastContent(request));
    }
    String user = lastContent(request);
    if (user.contains("refused")) {
      String outside = E2eWorkspace.slash(WS.forbidden.resolve("out.txt"));
      return call("file_write", "{\"path\":\"" + outside + "\",\"content\":\"x\"}");
    }
    if (user.contains("clean")) {
      return call(
          "file_read", "{\"path\":\"" + E2eWorkspace.slash(WS.allowed.resolve("ok.txt")) + "\"}");
    }
    return say("ok: " + user);
  }

  static {
    try {
      java.nio.file.Files.writeString(WS.allowed.resolve("ok.txt"), "fine");
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
    WS.profile(
        "corr",
        E2eWorkspace.FAKE_PROVIDER,
        List.of("file_write", "file_read"),
        E2eWorkspace.files(WS.allowed),
        "schedules:\n"
            + "  - id: refused\n    cron: \""
            + FAR_CRON
            + "\"\n    zone: UTC\n    message: 'refused write'\n"
            + "  - id: clean\n    cron: \""
            + FAR_CRON
            + "\"\n    zone: UTC\n    message: 'clean read'\n");
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry registry) {
    WS.register(registry, MODEL.baseUrl());
  }

  @AfterAll
  static void stop() {
    MODEL.close();
  }

  @Autowired ScheduleService schedules;
  @Autowired ToolCallHistory history;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;

  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("springAiAgentService")
  AgentService agent;

  private String outcomeOf(TaskExecution run, String field) throws Exception {
    String body =
        mvc.perform(
                get("/api/v1/schedules/" + run.taskId() + "/executions/" + run.id() + "/outcome"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .readTree(body)
        .at("/data/" + field)
        .asText();
  }

  @Test
  void twoTasksOfOneAgentRunningAtTheSameTimeAreAccountedSeparately() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch go = new CountDownLatch(1);
    Future<TaskExecution> refused =
        pool.submit(
            () -> {
              go.await();
              return schedules.runNow("corr.refused");
            });
    Future<TaskExecution> clean =
        pool.submit(
            () -> {
              go.await();
              return schedules.runNow("corr.clean");
            });
    go.countDown();
    TaskExecution refusedRun = refused.get(30, TimeUnit.SECONDS);
    TaskExecution cleanRun = clean.get(30, TimeUnit.SECONDS);
    pool.shutdownNow();

    assertNotNull(refusedRun.executionId());
    assertNotNull(cleanRun.executionId());
    assertNotEquals(refusedRun.executionId(), cleanRun.executionId());

    // The old lookup (same session, started..ended+1s) cannot tell them apart: it finds BOTH
    // calls for each run, because the two windows overlap. This is the defect being fixed.
    for (TaskExecution run : List.of(refusedRun, cleanRun)) {
      var window =
          history.find(
              run.sessionId(),
              run.startedAt(),
              run.startedAt().plus(Duration.ofMillis(run.durationMs() + 1000)),
              200);
      assertEquals(2, window.size(), "the time window mixes the two runs up");
    }

    // The execution id does not.
    assertEquals(1, history.findByExecution(refusedRun.executionId(), 200).size());
    assertEquals(1, history.findByExecution(cleanRun.executionId(), 200).size());
    assertEquals(
        "file_write", history.findByExecution(refusedRun.executionId(), 200).getFirst().toolName());
    assertEquals(
        "file_read", history.findByExecution(cleanRun.executionId(), 200).getFirst().toolName());

    assertEquals("COMPLETED_WITH_TOOL_ISSUES", outcomeOf(refusedRun, "outcome"));
    assertEquals("1", outcomeOf(refusedRun, "toolDenied"));
    assertEquals("0", outcomeOf(refusedRun, "toolSucceeded"));
    assertEquals("execution_id", outcomeOf(refusedRun, "matchedBy"));
    assertEquals("COMPLETED", outcomeOf(cleanRun, "outcome"));
    assertEquals("1", outcomeOf(cleanRun, "toolSucceeded"));
    assertEquals("0", outcomeOf(cleanRun, "toolDenied"));
  }

  @Test
  void theIdIsStoredOnTheExecutionRowAndOnEveryToolRowOfThatRun() throws Exception {
    TaskExecution run = schedules.runNow("corr.clean");

    String stored =
        jdbc.queryForObject(
            "SELECT execution_id FROM task_executions WHERE id = ?", String.class, run.id());
    assertEquals(run.executionId(), stored);
    List<String> toolIds =
        jdbc.queryForList(
            "SELECT execution_id FROM tool_invocations WHERE execution_id = ?",
            String.class,
            run.executionId());
    assertEquals(List.of(run.executionId()), toolIds);
  }

  @Test
  void theSameTaskRunTwiceGetsTwoIdsAndEachRunSeesOnlyItsOwnCall() throws Exception {
    TaskExecution first = schedules.runNow("corr.refused");
    TaskExecution second = schedules.runNow("corr.refused");

    assertNotEquals(first.executionId(), second.executionId());
    assertEquals("1", outcomeOf(first, "toolCalls"));
    assertEquals("1", outcomeOf(second, "toolCalls"));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tool_invocations WHERE execution_id IN (?, ?)",
            Integer.class,
            first.executionId(),
            second.executionId()));
  }

  @Test
  void aChatMessageLeavesNoExecutionIdAndAScheduledRunNeverTakesAChatCall() throws Exception {
    agent.process("api", "chatter", "corr", "clean read");
    TaskExecution run = schedules.runNow("corr.clean");

    Integer chatRowsWithAnId =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tool_invocations WHERE session_id = 'api:chatter:corr'"
                + " AND execution_id IS NOT NULL",
            Integer.class);
    assertEquals(0, chatRowsWithAnId);
    assertEquals(1, history.findByExecution(run.executionId(), 200).size());
    assertTrue(
        history.findByExecution(run.executionId(), 200).stream()
            .allMatch(c -> c.sessionId().startsWith("scheduler:")));
  }

  @Test
  void anUnknownOrEmptyExecutionIdFindsNothingAndTheFieldIsNullForOldRows() {
    assertEquals(List.of(), history.findByExecution("does-not-exist", 200));
    assertEquals(List.of(), history.findByExecution("", 200));
    assertEquals(List.of(), history.findByExecution(null, 200));
    assertNull(
        new TaskExecution(1, "a.b", "s", "cron", java.time.Instant.EPOCH, true, null, 1)
            .executionId());
  }
}
