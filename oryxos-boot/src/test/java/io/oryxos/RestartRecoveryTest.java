package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.schedule.ScheduleUpdate;
import io.oryxos.core.schedule.ScheduleView;
import io.oryxos.core.schedule.TaskExecution;
import io.oryxos.scheduler.AgentScheduler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Each {@code start()} is a brand-new Spring context on the same SQLite file, the in-JVM equivalent
 * of stopping and starting the process. Schedules are re-registered, but history, run counters and
 * the enabled switch must not be reset.
 */
class RestartRecoveryTest {
  @TempDir Path temp;

  private ScheduleTestEnvironment env;

  private ConfigurableApplicationContext start() {
    return new SpringApplicationBuilder(OryxOsApplication.class)
        .web(WebApplicationType.NONE)
        .bannerMode(Banner.Mode.OFF)
        .run(env.args().toArray(String[]::new));
  }

  private static ScheduleView view(AgentScheduler scheduler, String taskId) {
    return scheduler.list().stream()
        .filter(v -> v.taskId().equals(taskId))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void historyCountersAndTheEnabledSwitchSurviveARestart() throws IOException {
    env = new ScheduleTestEnvironment(temp);
    TaskExecution first;
    try (ConfigurableApplicationContext run1 = start()) {
      AgentScheduler scheduler = run1.getBean(AgentScheduler.class);
      first = scheduler.runNow("sched-demo.ok");
      scheduler.runNow("sched-demo.ok");
      scheduler.runNow("sched-broken.boom"); // a failure, to be remembered as well
      scheduler.update("sched-other.ok", new ScheduleUpdate(false, null, null, null));
      assertTrue(first.success());
    }

    try (ConfigurableApplicationContext run2 = start()) {
      AgentScheduler scheduler = run2.getBean(AgentScheduler.class);

      ScheduleView ok = view(scheduler, "sched-demo.ok");
      assertEquals(2, ok.runCount());
      assertEquals("SUCCESS", ok.lastStatus());
      assertNotNull(ok.lastRunAt());
      assertTrue(ok.enabled());
      assertNotNull(ok.nextRunAt(), "an enabled task gets a fresh next run time");

      ScheduleView broken = view(scheduler, "sched-broken.boom");
      assertEquals(1, broken.runCount());
      assertEquals("FAILED", broken.lastStatus());

      ScheduleView other = view(scheduler, "sched-other.ok");
      assertFalse(other.enabled(), "the disabled switch must survive a restart");
      assertNull(other.nextRunAt());
      assertEquals(0, other.runCount());

      List<TaskExecution> history = scheduler.executions("sched-demo.ok", 10);
      assertEquals(2, history.size());
      assertEquals(first.id(), history.get(1).id(), "the old execution row is the same row");
      assertEquals(first.startedAt(), history.get(1).startedAt());
      assertEquals(
          "Agent run failed (IllegalArgumentException)",
          scheduler.executions("sched-broken.boom", 10).getFirst().errorMessage());

      // The disabled task stays silent after the restart, an enabled one keeps counting on.
      scheduler.fire("sched-other.ok");
      assertEquals(0, scheduler.executions("sched-other.ok", 10).size());
      scheduler.runNow("sched-demo.ok");
      assertEquals(3, view(scheduler, "sched-demo.ok").runCount());

      // The session survived too: the third run continues the same conversation.
      String transcript =
          run2.getBean(JdbcTemplate.class)
              .queryForObject(
                  "SELECT messages_json FROM sessions WHERE session_id ="
                      + " 'scheduler:scheduler:sched-demo'",
                  String.class);
      assertEquals(3, transcript.split("Mock response: hello from the schedule", -1).length - 1);
    }

    // And a third start sees all of it again.
    try (ConfigurableApplicationContext run3 = start()) {
      AgentScheduler scheduler = run3.getBean(AgentScheduler.class);
      assertEquals(3, view(scheduler, "sched-demo.ok").runCount());
      assertEquals(3, scheduler.executions("sched-demo.ok", 10).size());
      assertFalse(view(scheduler, "sched-other.ok").enabled());
    }
  }

  @Test
  void aRuntimeEditSurvivesARestartButAChangedProfileFileWins() throws IOException {
    env = new ScheduleTestEnvironment(temp);
    try (ConfigurableApplicationContext run1 = start()) {
      run1.getBean(AgentScheduler.class)
          .update(
              "sched-demo.ok", new ScheduleUpdate(null, "0 0 6 1 1 *", "UTC", "edited at runtime"));
    }
    try (ConfigurableApplicationContext run2 = start()) {
      ScheduleView view = view(run2.getBean(AgentScheduler.class), "sched-demo.ok");
      assertEquals("0 0 6 1 1 *", view.cron(), "profile unchanged: the API edit is kept");
      assertEquals("edited at runtime", view.message());
      run2.getBean(AgentScheduler.class).runNow("sched-demo.ok");
    }

    // Edit the profile file: its new definition replaces the stored one, history stays.
    Path file = env.profiles.resolve("sched-demo.yaml");
    Files.writeString(
        file,
        Files.readString(file)
            .replace("hello from the schedule", "hello again, new text")
            .replace(
                "\"" + ScheduleTestEnvironment.FAR_CRON + "\"\n    zone: Asia/Shanghai",
                "\"0 0 4 1 1 *\"\n    zone: Asia/Shanghai"));
    try (ConfigurableApplicationContext run3 = start()) {
      AgentScheduler scheduler = run3.getBean(AgentScheduler.class);
      ScheduleView view = view(scheduler, "sched-demo.ok");
      assertEquals("0 0 4 1 1 *", view.cron());
      assertEquals("hello again, new text", view.message());
      assertEquals(1, view.runCount(), "a changed definition does not reset the history");
      assertEquals(1, scheduler.executions("sched-demo.ok", 10).size());
    }
  }

  @Test
  void aScheduleRemovedFromItsProfileKeepsItsHistoryInTheDatabase() throws IOException {
    env = new ScheduleTestEnvironment(temp);
    try (ConfigurableApplicationContext run1 = start()) {
      run1.getBean(AgentScheduler.class).runNow("sched-other.ok");
    }
    Files.delete(env.profiles.resolve("sched-other.yaml"));
    try (ConfigurableApplicationContext run2 = start()) {
      AgentScheduler scheduler = run2.getBean(AgentScheduler.class);
      assertTrue(scheduler.list().stream().noneMatch(v -> v.taskId().equals("sched-other.ok")));
      JdbcTemplate jdbc = run2.getBean(JdbcTemplate.class);
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM task_executions WHERE task_id = 'sched-other.ok'",
              Integer.class));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT run_count FROM scheduled_tasks WHERE task_id = 'sched-other.ok'",
              Integer.class));
    }
  }

  @Test
  void withTheSchedulerSwitchedOffNothingIsRegisteredAndManualRunsAreRefused() throws IOException {
    env = new ScheduleTestEnvironment(temp);
    try (ConfigurableApplicationContext run1 = start()) {
      run1.getBean(AgentScheduler.class).runNow("sched-demo.ok");
    }
    ConfigurableApplicationContext off =
        new SpringApplicationBuilder(OryxOsApplication.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .run(env.args("--oryxos.scheduler.enabled=false").toArray(String[]::new));
    try (off) {
      AgentScheduler scheduler = off.getBean(AgentScheduler.class);
      assertFalse(scheduler.schedulerEnabled());
      org.junit.jupiter.api.Assertions.assertThrows(
          io.oryxos.core.schedule.SchedulerDisabledException.class,
          () -> scheduler.runNow("sched-demo.ok"));
      // Stored state stays readable.
      assertEquals(1, view(scheduler, "sched-demo.ok").runCount());
    }
  }
}
