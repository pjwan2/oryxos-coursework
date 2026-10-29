package io.oryxos.scheduler;

import static io.oryxos.scheduler.SchedulerFixtures.CLOCK;
import static io.oryxos.scheduler.SchedulerFixtures.NOW;
import static io.oryxos.scheduler.SchedulerFixtures.profile;
import static io.oryxos.scheduler.SchedulerFixtures.schedule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.AgentMessage;
import io.oryxos.core.agent.ModelGateway;
import io.oryxos.core.agent.ModelTurn;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.schedule.ScheduleConfigException;
import io.oryxos.core.schedule.ScheduleNotFoundException;
import io.oryxos.core.schedule.ScheduleUpdate;
import io.oryxos.core.schedule.ScheduleView;
import io.oryxos.core.schedule.ScheduledTask;
import io.oryxos.core.schedule.SchedulerDisabledException;
import io.oryxos.core.schedule.TaskAlreadyRunningException;
import io.oryxos.core.schedule.TaskExecution;
import io.oryxos.core.tool.ToolSpec;
import io.oryxos.scheduler.SchedulerFixtures.InMemorySessions;
import io.oryxos.scheduler.SchedulerFixtures.InMemoryTaskStore;
import io.oryxos.scheduler.SchedulerFixtures.RecordingModel;
import io.oryxos.scheduler.SchedulerFixtures.RecordingTaskScheduler;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.SimpleTriggerContext;

class AgentSchedulerTest {
  private static final String SECRET = "TOKEN-ABC123?key=SECRETKEY";

  private final InMemoryTaskStore store = new InMemoryTaskStore();
  private final InMemorySessions sessions = new InMemorySessions();
  private final RecordingTaskScheduler triggers = new RecordingTaskScheduler();
  private final ExecutorService pool = Executors.newCachedThreadPool();

  @AfterEach
  void shutDown() {
    pool.shutdownNow();
  }

  private AgentScheduler scheduler(ModelGateway model, boolean enabled, AgentProfile... profiles) {
    List<AgentProfile> list = List.of(profiles);
    return new AgentScheduler(
        SchedulerFixtures.agentService(model, list, sessions),
        () -> list,
        store,
        triggers,
        CLOCK,
        enabled);
  }

  private static AgentProfile weather() {
    return profile(
        "weather", List.of(schedule("morning", "0 0 9 * * *", "Asia/Shanghai", "Weather please")));
  }

  // ---- registration ---------------------------------------------------------------------

  @Test
  void cronAndZoneAreRegisteredPerTaskSoTheSameWallClockMeansDifferentInstants() {
    AgentScheduler scheduler =
        scheduler(
            new RecordingModel(),
            true,
            profile("shanghai", List.of(schedule("nine", "0 0 9 * * *", "Asia/Shanghai", "m"))),
            profile("newyork", List.of(schedule("nine", "0 0 9 * * *", "America/New_York", "m"))));
    scheduler.start();

    assertEquals(2, triggers.registrations.size());
    var context = new SimpleTriggerContext(CLOCK);
    // 2026-03-01T00:00Z: 09:00 in Shanghai (+08) is 01:00Z, 09:00 in New York (-05) is 14:00Z.
    assertEquals(
        Instant.parse("2026-03-01T01:00:00Z"),
        triggers.registrations.get(0).trigger().nextExecution(context));
    assertEquals(
        Instant.parse("2026-03-01T14:00:00Z"),
        triggers.registrations.get(1).trigger().nextExecution(context));

    assertEquals(
        Instant.parse("2026-03-01T01:00:00Z"), store.tasks.get("shanghai.nine").nextRunAt());
    assertEquals(
        Instant.parse("2026-03-01T14:00:00Z"), store.tasks.get("newyork.nine").nextRunAt());
    assertEquals("America/New_York", store.tasks.get("newyork.nine").zone());
  }

  @Test
  void theSameLocalIdInTwoProfilesGivesTwoIndependentTasks() {
    AgentScheduler scheduler =
        scheduler(
            new RecordingModel(),
            true,
            profile("a", List.of(schedule("daily", "0 0 9 * * *", "UTC", "from a"))),
            profile("b", List.of(schedule("daily", "0 0 9 * * *", "UTC", "from b"))));
    scheduler.start();
    assertEquals(
        List.of("a.daily", "b.daily"),
        scheduler.list().stream().map(ScheduleView::taskId).toList());
    assertEquals("from a", store.tasks.get("a.daily").message());
    assertEquals("from b", store.tasks.get("b.daily").message());
  }

  @Test
  void startingTwiceDoesNotRegisterTwice() {
    AgentScheduler scheduler = scheduler(new RecordingModel(), true, weather());
    scheduler.start();
    scheduler.start();
    assertEquals(1, triggers.registrations.size());
    assertTrue(scheduler.isRunning());
  }

  @Test
  void aGloballyDisabledSchedulerRegistersNothingAndRefusesChanges() {
    AgentScheduler scheduler = scheduler(new RecordingModel(), false, weather());
    scheduler.start();
    assertTrue(triggers.registrations.isEmpty());
    assertTrue(store.tasks.isEmpty());
    assertThrows(SchedulerDisabledException.class, () -> scheduler.runNow("weather.morning"));
    assertThrows(
        SchedulerDisabledException.class,
        () -> scheduler.update("weather.morning", new ScheduleUpdate(false, null, null, null)));
  }

  @Test
  void anInvalidScheduleStopsConstructionEvenWhenTheSchedulerIsDisabled() {
    assertThrows(
        ScheduleConfigException.class,
        () ->
            scheduler(
                new RecordingModel(),
                false,
                profile("bad", List.of(schedule("x", "garbage", "UTC", "m")))));
  }

  @Test
  void stoppingCancelsTheTriggers() {
    AgentScheduler scheduler = scheduler(new RecordingModel(), true, weather());
    scheduler.start();
    scheduler.stop();
    assertTrue(triggers.last().future().isCancelled());
    assertFalse(scheduler.isRunning());
  }

  // ---- executing ------------------------------------------------------------------------

  @Test
  void aCronFiringRunsTheAgentAndUpdatesStatusCountersAndHistory() {
    RecordingModel model = new RecordingModel();
    AgentScheduler scheduler = scheduler(model, true, weather());
    scheduler.start();

    triggers.last().task().run();

    assertEquals(List.of("scheduler:scheduler:weather"), model.sessionIds);
    ScheduledTask task = store.tasks.get("weather.morning");
    assertEquals(1, task.runCount());
    assertEquals("SUCCESS", task.lastStatus());
    assertEquals(NOW, task.lastRunAt());
    assertEquals(Instant.parse("2026-03-01T01:00:00Z"), task.nextRunAt());
    TaskExecution run = store.history.getFirst();
    assertTrue(run.success());
    assertNull(run.errorMessage());
    assertEquals("cron", run.trigger());
    assertEquals("scheduler:scheduler:weather", run.sessionId());
  }

  @Test
  void theSchedulerIdentityIsFixedAndTheSessionComesFromTheSharedSessionIds() {
    RecordingModel model = new RecordingModel();
    AgentScheduler scheduler = scheduler(model, true, weather());
    scheduler.start();
    triggers.last().task().run();
    assertEquals("scheduler", AgentScheduler.CHANNEL);
    assertEquals("scheduler", AgentScheduler.USER);
    assertEquals(List.of("scheduler:scheduler:weather"), List.copyOf(sessions.sessions.keySet()));
  }

  @Test
  void consecutiveTriggersReuseTheSameSessionAndGrowItsHistory() {
    RecordingModel model = new RecordingModel();
    AgentScheduler scheduler = scheduler(model, true, weather());
    scheduler.start();

    triggers.last().task().run();
    triggers.last().task().run();
    scheduler.runNow("weather.morning");

    assertEquals(1, sessions.sessions.size());
    List<AgentMessage> transcript =
        sessions.sessions.get("scheduler:scheduler:weather").transcript();
    assertEquals(6, transcript.size(), "three runs, each a user message and an answer");
    assertEquals(3, store.tasks.get("weather.morning").runCount());
  }

  @Test
  void runNowRunsImmediatelyWithoutWaitingForTheCronAndMarksItManual() {
    RecordingModel model = new RecordingModel();
    AgentScheduler scheduler = scheduler(model, true, weather());
    scheduler.start();

    TaskExecution run = scheduler.runNow("weather.morning");

    assertTrue(run.success());
    assertEquals("manual", run.trigger());
    assertTrue(run.id() > 0);
    assertEquals(1, model.sessionIds.size());
    assertEquals(1, scheduler.executions("weather.morning", 10).size());
  }

  // ---- overlap --------------------------------------------------------------------------

  /** A model that parks until released, so a run can be observed while it is in flight. */
  private static final class BlockingModel implements ModelGateway {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public ModelTurn chat(
        String sessionId, AgentProfile profile, List<AgentMessage> prompt, List<ToolSpec> tools) {
      calls.incrementAndGet();
      entered.countDown();
      try {
        if (!release.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("test timeout");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      return ModelTurn.finalAnswer("done");
    }
  }

  @Test
  void aSecondTriggerWhileTheTaskIsRunningDoesNotOverlap() throws Exception {
    BlockingModel model = new BlockingModel();
    AgentScheduler scheduler = scheduler(model, true, weather());
    scheduler.start();

    Future<?> first = pool.submit(() -> triggers.last().task().run());
    assertTrue(model.entered.await(5, TimeUnit.SECONDS));
    assertTrue(scheduler.list().getFirst().running());

    triggers.last().task().run(); // the next cron firing arrives while the first still runs
    assertEquals(1, model.calls.get(), "the overlapping firing must not call the model");
    assertEquals(0, store.history.size(), "a skipped firing writes no history");

    model.release.countDown();
    first.get(5, TimeUnit.SECONDS);
    assertEquals(1, store.history.size());
    assertFalse(scheduler.list().getFirst().running());
  }

  @Test
  void runNowWhileACronRunIsInFlightIsRejectedAndTheOtherWayAround() throws Exception {
    BlockingModel model = new BlockingModel();
    AgentScheduler scheduler = scheduler(model, true, weather());
    scheduler.start();

    Future<?> cron = pool.submit(() -> triggers.last().task().run());
    assertTrue(model.entered.await(5, TimeUnit.SECONDS));
    assertThrows(TaskAlreadyRunningException.class, () -> scheduler.runNow("weather.morning"));

    model.release.countDown();
    cron.get(5, TimeUnit.SECONDS);

    // Lock released: a manual run now works, and a cron firing during it is the one skipped.
    BlockingModel second = new BlockingModel();
    AgentScheduler other =
        scheduler(second, true, profile("w2", List.of(schedule("m", "0 0 9 * * *", "UTC", "x"))));
    other.start();
    Future<?> manual = pool.submit(() -> other.runNow("w2.m"));
    assertTrue(second.entered.await(5, TimeUnit.SECONDS));
    triggers.last().task().run();
    assertEquals(1, second.calls.get());
    second.release.countDown();
    manual.get(5, TimeUnit.SECONDS);
  }

  @Test
  void manyConcurrentTriggersRunTheTaskExactlyOnce() throws Exception {
    BlockingModel model = new BlockingModel();
    AgentScheduler scheduler = scheduler(model, true, weather());
    scheduler.start();

    int threads = 16;
    CountDownLatch go = new CountDownLatch(1);
    List<Future<?>> all = new java.util.ArrayList<>();
    for (int i = 0; i < threads; i++) {
      all.add(
          pool.submit(
              () -> {
                go.await();
                triggers.last().task().run();
                return null;
              }));
    }
    go.countDown();
    assertTrue(model.entered.await(5, TimeUnit.SECONDS));
    Thread.sleep(200); // let the losers try and give up
    assertEquals(1, model.calls.get());
    model.release.countDown();
    for (Future<?> f : all) {
      f.get(5, TimeUnit.SECONDS);
    }
    assertEquals(1, store.history.size());
  }

  // ---- enabled flag ---------------------------------------------------------------------

  @Test
  void aDisabledTaskIsSkippedByCronWithoutHistoryButCanStillBeRunByHand() {
    RecordingModel model = new RecordingModel();
    AgentScheduler scheduler = scheduler(model, true, weather());
    scheduler.start();
    scheduler.update("weather.morning", new ScheduleUpdate(false, null, null, null));

    triggers.last().task().run();
    assertTrue(model.sessionIds.isEmpty());
    assertTrue(store.history.isEmpty());
    assertEquals(0, store.tasks.get("weather.morning").runCount());
    assertNull(store.tasks.get("weather.morning").nextRunAt());

    assertTrue(scheduler.runNow("weather.morning").success(), "a manual run is a deliberate act");
    assertEquals(1, model.sessionIds.size());
    assertFalse(store.tasks.get("weather.morning").enabled(), "running by hand keeps it disabled");
  }

  @Test
  void reEnablingRestoresCronExecutionAndTheNextRunTime() {
    RecordingModel model = new RecordingModel();
    AgentScheduler scheduler = scheduler(model, true, weather());
    scheduler.start();
    scheduler.update("weather.morning", new ScheduleUpdate(false, null, null, null));
    ScheduleView view =
        scheduler.update("weather.morning", new ScheduleUpdate(true, null, null, null));

    assertTrue(view.enabled());
    assertEquals(Instant.parse("2026-03-01T01:00:00Z"), view.nextRunAt());
    triggers.last().task().run();
    assertEquals(1, model.sessionIds.size());
  }

  // ---- failure --------------------------------------------------------------------------

  /** Fails with a message that contains a credential, the first {@code failures} times. */
  private static final class FlakyModel implements ModelGateway {
    final AtomicInteger failuresLeft;

    FlakyModel(int failures) {
      failuresLeft = new AtomicInteger(failures);
    }

    @Override
    public ModelTurn chat(
        String sessionId, AgentProfile profile, List<AgentMessage> prompt, List<ToolSpec> tools) {
      if (failuresLeft.getAndDecrement() > 0) {
        throw new IllegalStateException("upstream said https://api.example.org/v1?" + SECRET);
      }
      return ModelTurn.finalAnswer("recovered");
    }
  }

  @Test
  void aFailedRunIsRecordedWithoutTheRawMessageAndTheLockIsReleased() {
    AgentScheduler scheduler = scheduler(new FlakyModel(1), true, weather());
    scheduler.start();

    triggers.last().task().run(); // fails, and must not throw out of the scheduler thread

    TaskExecution failed = store.history.getFirst();
    assertFalse(failed.success());
    assertEquals("Agent run failed (IllegalStateException)", failed.errorMessage());
    assertFalse(failed.errorMessage().contains("SECRETKEY"));
    ScheduledTask task = store.tasks.get("weather.morning");
    assertEquals("FAILED", task.lastStatus());
    assertEquals(1, task.runCount());
    assertFalse(scheduler.list().getFirst().running(), "lock released after a failure");

    triggers.last().task().run(); // the next firing works
    assertTrue(store.history.get(1).success());
    assertEquals("SUCCESS", store.tasks.get("weather.morning").lastStatus());
    assertEquals(2, store.tasks.get("weather.morning").runCount());
  }

  @Test
  void aFailedManualRunIsReturnedNotThrown() {
    AgentScheduler scheduler = scheduler(new FlakyModel(1), true, weather());
    scheduler.start();
    TaskExecution run = scheduler.runNow("weather.morning");
    assertFalse(run.success());
    assertEquals("manual", run.trigger());
    assertFalse(String.valueOf(run).contains("SECRETKEY"));
  }

  @Test
  void aProfileThatNoLongerExistsFailsTheRunInsteadOfTheScheduler() {
    // The profile supplier still lists "ghost", but the agent cannot resolve it any more.
    AgentProfile ghost = profile("ghost", List.of(schedule("g", "0 0 9 * * *", "UTC", "m")));
    AgentScheduler scheduler =
        new AgentScheduler(
            SchedulerFixtures.agentService(new RecordingModel(), List.of(), sessions),
            () -> List.of(ghost),
            store,
            triggers,
            CLOCK,
            true);
    scheduler.start();
    triggers.last().task().run();
    assertEquals("FAILED", store.tasks.get("ghost.g").lastStatus());
    assertEquals(
        "Agent run failed (IllegalArgumentException)", store.history.getFirst().errorMessage());
  }

  @Test
  void aStoreFailureWhileRecordingDoesNotEscapeAndDoesNotLeaveTheTaskLocked() {
    AgentScheduler scheduler = scheduler(new RecordingModel(), true, weather());
    scheduler.start();
    store.failRecording = true;

    TaskExecution run = scheduler.runNow("weather.morning");
    assertTrue(run.success(), "the run itself succeeded; only its bookkeeping failed");
    triggers.last().task().run(); // must not throw either

    store.failRecording = false;
    assertTrue(scheduler.runNow("weather.morning").success(), "the lock was released");
  }

  // ---- updates and lookups --------------------------------------------------------------

  @Test
  void changingTheCronReRegistersTheTriggerAndCancelsTheOldOne() {
    AgentScheduler scheduler = scheduler(new RecordingModel(), true, weather());
    scheduler.start();
    var old = triggers.last().future();

    ScheduleView view =
        scheduler.update("weather.morning", new ScheduleUpdate(null, "0 30 18 * * *", "UTC", null));

    assertTrue(old.isCancelled());
    assertEquals(2, triggers.registrations.size());
    assertEquals("0 30 18 * * *", view.cron());
    assertEquals(Instant.parse("2026-03-01T18:30:00Z"), view.nextRunAt());
    assertEquals(
        Instant.parse("2026-03-01T18:30:00Z"),
        triggers.last().trigger().nextExecution(new SimpleTriggerContext(CLOCK)));
  }

  @Test
  void anInvalidUpdateChangesNothing() {
    AgentScheduler scheduler = scheduler(new RecordingModel(), true, weather());
    scheduler.start();
    ScheduledTask before = store.tasks.get("weather.morning");

    assertThrows(
        ScheduleConfigException.class,
        () -> scheduler.update("weather.morning", new ScheduleUpdate(null, "bogus", null, null)));
    assertThrows(
        ScheduleConfigException.class,
        () -> scheduler.update("weather.morning", new ScheduleUpdate(null, null, "Mars/X", null)));
    assertThrows(
        ScheduleConfigException.class,
        () -> scheduler.update("weather.morning", new ScheduleUpdate(null, null, null, " ")));
    assertThrows(
        ScheduleConfigException.class,
        () -> scheduler.update("weather.morning", new ScheduleUpdate(null, null, null, null)));
    assertThrows(
        ScheduleConfigException.class,
        () ->
            scheduler.update(
                "weather.morning", new ScheduleUpdate(null, "* * * * * *", null, null)));

    assertEquals(before, store.tasks.get("weather.morning"));
    assertEquals(1, triggers.registrations.size());
  }

  @Test
  void aMessageEditIsPersistedAndUsedByTheNextRun() {
    RecordingModel model = new RecordingModel();
    AgentScheduler scheduler = scheduler(model, true, weather());
    scheduler.start();
    scheduler.update("weather.morning", new ScheduleUpdate(null, null, null, "A new question"));
    scheduler.runNow("weather.morning");
    var transcript = sessions.sessions.get("scheduler:scheduler:weather").transcript();
    assertEquals("A new question", transcript.getFirst().content());
  }

  @Test
  void unknownTaskIdsAreNotFoundEverywhere() {
    AgentScheduler scheduler = scheduler(new RecordingModel(), true, weather());
    scheduler.start();
    assertThrows(ScheduleNotFoundException.class, () -> scheduler.runNow("weather.nope"));
    assertThrows(ScheduleNotFoundException.class, () -> scheduler.runNow("nope"));
    assertThrows(ScheduleNotFoundException.class, () -> scheduler.runNow(null));
    assertThrows(
        ScheduleNotFoundException.class,
        () -> scheduler.update("nope.nope", new ScheduleUpdate(true, null, null, null)));
    assertThrows(ScheduleNotFoundException.class, () -> scheduler.executions("nope.nope", 5));
  }

  @Test
  void executionsAreListedNewestFirstAndRespectTheLimit() {
    AgentScheduler scheduler = scheduler(new RecordingModel(), true, weather());
    scheduler.start();
    for (int i = 0; i < 3; i++) {
      scheduler.runNow("weather.morning");
    }
    List<TaskExecution> latest = scheduler.executions("weather.morning", 2);
    assertEquals(2, latest.size());
    assertTrue(latest.get(0).id() > latest.get(1).id());
  }

  @Test
  void aTaskRemovedFromItsProfileIsNoLongerListedOrRunnableButItsHistoryStays() {
    RecordingModel model = new RecordingModel();
    AgentScheduler first = scheduler(model, true, weather());
    first.start();
    first.runNow("weather.morning");

    // Restart with the schedule gone from the profile (same store).
    AgentScheduler second = scheduler(model, true, profile("weather", List.of()));
    second.start();
    assertTrue(second.list().isEmpty());
    assertThrows(ScheduleNotFoundException.class, () -> second.runNow("weather.morning"));
    assertEquals(1, store.history.size());
  }

  @Test
  void aZoneIdIsKeptInItsCanonicalForm() {
    AgentScheduler scheduler = scheduler(new RecordingModel(), true, weather());
    scheduler.start();
    assertEquals(ZoneId.of("Asia/Shanghai").getId(), store.tasks.get("weather.morning").zone());
  }
}
