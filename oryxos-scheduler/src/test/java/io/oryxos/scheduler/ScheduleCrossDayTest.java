package io.oryxos.scheduler;

import static io.oryxos.scheduler.SchedulerFixtures.profile;
import static io.oryxos.scheduler.SchedulerFixtures.schedule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.oryxos.core.agent.ModelGateway;
import io.oryxos.core.agent.ModelTurn;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.schedule.ScheduleUpdate;
import io.oryxos.core.schedule.TaskExecution;
import io.oryxos.scheduler.SchedulerFixtures.InMemorySessions;
import io.oryxos.scheduler.SchedulerFixtures.InMemoryTaskStore;
import io.oryxos.scheduler.SchedulerFixtures.RecordingTaskScheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.SimpleTriggerContext;

/**
 * "Two mornings in a row", without waiting two days: a clock the test moves, the cron trigger the
 * scheduler really registered, and the real run path. A real 48-hour unattended run has NOT been
 * observed; this is the deterministic stand-in the course material allows.
 */
class ScheduleCrossDayTest {
  /** 08:00 in Shanghai is 00:00 UTC (no daylight saving there). */
  private static final String EIGHT_AM_SHANGHAI = "0 0 8 * * *";

  private static final class MovableClock extends Clock {
    private Instant now;

    MovableClock(Instant start) {
      this.now = start;
    }

    void moveTo(Instant instant) {
      now = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final InMemoryTaskStore store = new InMemoryTaskStore();
  private final InMemorySessions sessions = new InMemorySessions();
  private final RecordingTaskScheduler triggers = new RecordingTaskScheduler();
  private final MovableClock clock = new MovableClock(Instant.parse("2026-03-05T22:30:00Z"));

  private AgentScheduler started(ModelGateway model) {
    AgentProfile weather =
        profile(
            "weather",
            List.of(schedule("morning", EIGHT_AM_SHANGHAI, "Asia/Shanghai", "report please")));
    AgentScheduler scheduler =
        new AgentScheduler(
            SchedulerFixtures.agentService(model, List.of(weather), sessions),
            () -> List.of(weather),
            store,
            triggers,
            clock,
            true);
    scheduler.start();
    return scheduler;
  }

  private Instant nextFire() {
    return triggers
        .registrations
        .getFirst()
        .trigger()
        .nextExecution(new SimpleTriggerContext(clock));
  }

  private static ModelGateway answering(String text) {
    return (sessionId, profile, prompt, tools) -> ModelTurn.finalAnswer(text);
  }

  @Test
  void theTriggerIsDueAtEightInTheMorningLocalTimeEveryDay() {
    started(answering("ok"));

    Instant first = nextFire();
    assertEquals(
        Instant.parse("2026-03-06T00:00:00Z"),
        first,
        "06:30 in Shanghai -> 08:00 the same morning");

    clock.moveTo(first.plusSeconds(1));
    assertEquals(Instant.parse("2026-03-07T00:00:00Z"), nextFire(), "and exactly one day later");
  }

  @Test
  void twoConsecutiveMorningsGiveTwoSeparateCronRunsWithTheirOwnIdsAndOneGrowingSession() {
    AgentScheduler scheduler = started(answering("sent"));
    Runnable tick = triggers.registrations.getFirst().task();

    clock.moveTo(nextFire());
    tick.run(); // day 1, 08:00
    clock.moveTo(nextFire());
    tick.run(); // day 2, 08:00

    List<TaskExecution> runs = scheduler.executions("weather.morning", 10);
    assertEquals(2, runs.size());
    TaskExecution dayTwo = runs.get(0);
    TaskExecution dayOne = runs.get(1);
    assertEquals("cron", dayOne.trigger());
    assertEquals("cron", dayTwo.trigger());
    assertEquals(Instant.parse("2026-03-06T00:00:00Z"), dayOne.startedAt());
    assertEquals(Instant.parse("2026-03-07T00:00:00Z"), dayTwo.startedAt());
    assertEquals(Duration.ofDays(1), Duration.between(dayOne.startedAt(), dayTwo.startedAt()));
    assertNotNull(dayOne.executionId());
    assertNotEquals(dayOne.executionId(), dayTwo.executionId());
    assertEquals(2, store.tasks.get("weather.morning").runCount());
    assertEquals("SUCCESS", store.tasks.get("weather.morning").lastStatus());
    assertEquals(
        Instant.parse("2026-03-08T00:00:00Z"),
        store.tasks.get("weather.morning").nextRunAt(),
        "the third morning is already scheduled");
    assertEquals(
        4,
        sessions.sessions.get("scheduler:scheduler:weather").transcript().size(),
        "two questions, two answers");
  }

  @Test
  void aFailureOnTheFirstMorningDoesNotStopTheSecond() {
    AtomicInteger calls = new AtomicInteger();
    ModelGateway flaky =
        (sessionId, profile, prompt, tools) -> {
          if (calls.incrementAndGet() == 1) {
            throw new IllegalStateException("provider down");
          }
          return ModelTurn.finalAnswer("recovered");
        };
    AgentScheduler scheduler = started(flaky);
    Runnable tick = triggers.registrations.getFirst().task();

    clock.moveTo(nextFire());
    tick.run();
    clock.moveTo(nextFire());
    tick.run();

    List<TaskExecution> runs = scheduler.executions("weather.morning", 10);
    assertEquals(false, runs.get(1).success(), "day one failed");
    assertEquals("Agent run failed (IllegalStateException)", runs.get(1).errorMessage());
    assertEquals(true, runs.get(0).success(), "day two ran normally");
    assertEquals("SUCCESS", store.tasks.get("weather.morning").lastStatus());
    assertEquals(2, store.tasks.get("weather.morning").runCount());
  }

  @Test
  void aTaskSwitchedOffBetweenTheMorningsSkipsTheSecondOneAndKeepsItsHistory() {
    AgentScheduler scheduler = started(answering("sent"));
    Runnable tick = triggers.registrations.getFirst().task();

    clock.moveTo(nextFire());
    tick.run();
    scheduler.update("weather.morning", new ScheduleUpdate(false, null, null, null));
    clock.moveTo(nextFire());
    tick.run(); // the trigger still fires, the scheduler declines

    assertEquals(1, scheduler.executions("weather.morning", 10).size());
    assertEquals(1, store.tasks.get("weather.morning").runCount());
    assertNull(store.tasks.get("weather.morning").nextRunAt(), "a disabled task has no next run");
  }
}
