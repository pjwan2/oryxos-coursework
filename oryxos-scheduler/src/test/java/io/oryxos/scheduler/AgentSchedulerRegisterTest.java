package io.oryxos.scheduler;

import static io.oryxos.scheduler.SchedulerFixtures.CLOCK;
import static io.oryxos.scheduler.SchedulerFixtures.profile;
import static io.oryxos.scheduler.SchedulerFixtures.schedule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.schedule.ScheduleConfigException;
import io.oryxos.core.schedule.ScheduleNotFoundException;
import io.oryxos.core.schedule.ScheduleUpdate;
import io.oryxos.core.schedule.ScheduleView;
import io.oryxos.scheduler.SchedulerFixtures.InMemorySessions;
import io.oryxos.scheduler.SchedulerFixtures.InMemoryTaskStore;
import io.oryxos.scheduler.SchedulerFixtures.RecordingModel;
import io.oryxos.scheduler.SchedulerFixtures.RecordingTaskScheduler;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Agents come and go while the application runs: the scheduler must follow without losing state.
 */
class AgentSchedulerRegisterTest {
  private final InMemoryTaskStore store = new InMemoryTaskStore();
  private final InMemorySessions sessions = new InMemorySessions();
  private final RecordingTaskScheduler triggers = new RecordingTaskScheduler();
  private final RecordingModel model = new RecordingModel();

  private AgentScheduler started(AgentProfile... initial) {
    List<AgentProfile> all = List.of(initial);
    AgentScheduler scheduler =
        new AgentScheduler(
            SchedulerFixtures.agentService(
                model,
                List.of(profileWithoutSchedule("late"), profileWithoutSchedule("w")),
                sessions),
            () -> all,
            store,
            triggers,
            CLOCK,
            true);
    scheduler.start();
    return scheduler;
  }

  private static AgentProfile profileWithoutSchedule(String name) {
    return profile(name, List.of());
  }

  private static AgentProfile daily(String name, String cron) {
    return profile(name, List.of(schedule("daily", cron, "UTC", "run " + name)));
  }

  @Test
  void aProfileRegisteredAfterStartGetsItsTriggerAtOnce() {
    AgentScheduler scheduler = started();
    assertEquals(0, triggers.registrations.size());

    scheduler.registerProfile(daily("late", "0 0 9 * * *"));

    assertEquals(1, triggers.registrations.size());
    assertEquals(
        List.of("late.daily"), scheduler.list().stream().map(ScheduleView::taskId).toList());
    assertTrue(store.tasks.get("late.daily").enabled());
  }

  @Test
  void registeringTheSameProfileTwiceChangesNothing() {
    AgentScheduler scheduler = started();
    scheduler.registerProfile(daily("late", "0 0 9 * * *"));
    scheduler.registerProfile(daily("late", "0 0 9 * * *"));
    scheduler.registerProfile(daily("late", "0 0 9 * * *"));

    assertEquals(1, triggers.registrations.size(), "no second trigger for an unchanged schedule");
    assertFalse(triggers.registrations.getFirst().future().cancelled.get());
    assertEquals(1, scheduler.list().size());
  }

  @Test
  void aChangedScheduleCancelsTheOldTriggerBeforeTheNewOneIsRegistered() {
    AgentScheduler scheduler = started();
    scheduler.registerProfile(daily("late", "0 0 9 * * *"));
    var first = triggers.registrations.getFirst();

    scheduler.registerProfile(daily("late", "0 30 18 * * *"));

    assertTrue(first.future().cancelled.get(), "the old trigger was cancelled");
    assertEquals(2, triggers.registrations.size());
    assertFalse(triggers.last().future().cancelled.get());
    assertEquals("0 30 18 * * *", store.tasks.get("late.daily").cron());
    assertEquals(1, scheduler.list().size(), "the task keeps one stable identity");
  }

  @Test
  void aScheduleTheProfileNoLongerDeclaresIsStoppedButItsHistoryStays() {
    AgentScheduler scheduler = started();
    scheduler.registerProfile(daily("late", "0 0 9 * * *"));
    scheduler.runNow("late.daily");
    var trigger = triggers.last();

    scheduler.registerProfile(profileWithoutSchedule("late"));

    assertTrue(trigger.future().cancelled.get());
    assertThrows(ScheduleNotFoundException.class, () -> scheduler.runNow("late.daily"));
    assertEquals(1, scheduler.executions("late.daily", 10).size(), "history is kept");
    assertEquals(1, store.tasks.get("late.daily").runCount());
  }

  @Test
  void unregisteringAnAgentStopsItsTriggersAndKeepsTheRows() {
    AgentScheduler scheduler = started();
    scheduler.registerProfile(daily("late", "0 0 9 * * *"));
    scheduler.runNow("late.daily");

    scheduler.unregisterProfile("late");

    assertTrue(triggers.last().future().cancelled.get());
    assertEquals(List.of(), scheduler.list());
    assertEquals(1, scheduler.executions("late.daily", 10).size());
    assertTrue(store.tasks.containsKey("late.daily"), "the stored row is history, not deleted");
  }

  @Test
  void aTaskTheOperatorDisabledStaysDisabledAndKeepsItsCountWhenTheAgentIsReRegistered() {
    AgentScheduler scheduler = started();
    scheduler.registerProfile(daily("late", "0 0 9 * * *"));
    scheduler.runNow("late.daily");
    scheduler.update("late.daily", new ScheduleUpdate(false, null, null, null));

    scheduler.unregisterProfile("late");
    scheduler.registerProfile(daily("late", "0 0 9 * * *"));
    scheduler.registerProfile(daily("late", "0 15 9 * * *"));

    assertFalse(store.tasks.get("late.daily").enabled(), "never silently re-enabled");
    assertEquals(1, store.tasks.get("late.daily").runCount(), "the run count is not reset");
    assertEquals(1, scheduler.executions("late.daily", 10).size());
  }

  @Test
  void anInvalidScheduleIsRejectedAndLeavesTheExistingRegistrationAlone() {
    AgentScheduler scheduler = started();
    scheduler.registerProfile(daily("late", "0 0 9 * * *"));
    var live = triggers.last();

    assertThrows(
        ScheduleConfigException.class,
        () -> scheduler.registerProfile(daily("late", "not a cron")));

    assertFalse(live.future().cancelled.get(), "the working schedule is still registered");
    assertEquals("0 0 9 * * *", store.tasks.get("late.daily").cron());
    assertEquals(1, triggers.registrations.size());
  }

  @Test
  void aCronFiringForAnUnregisteredTaskIsIgnored() {
    AgentScheduler scheduler = started();
    scheduler.registerProfile(daily("late", "0 0 9 * * *"));
    scheduler.unregisterProfile("late");

    scheduler.fire("late.daily");

    assertEquals(0, model.sessionIds.size(), "no model call for a task that was removed");
    assertEquals(0, store.history.size());
  }

  @Test
  void profilesRegisteredBeforeStartAreActivatedByStartNotBefore() {
    List<AgentProfile> none = List.of();
    AgentScheduler scheduler =
        new AgentScheduler(
            SchedulerFixtures.agentService(
                model, List.of(profileWithoutSchedule("late")), sessions),
            () -> none,
            store,
            triggers,
            CLOCK,
            true);
    scheduler.registerProfile(daily("late", "0 0 9 * * *"));
    assertEquals(0, triggers.registrations.size(), "not started yet: nothing is triggered");

    scheduler.start();

    assertEquals(1, triggers.registrations.size());
  }

  @Test
  void aRegisteredDynamicScheduleRunsThroughTheSameAgentPathAsAManualRun() {
    AgentScheduler scheduler = started();
    scheduler.registerProfile(daily("late", "0 0 9 * * *"));

    triggers.last().task().run();
    scheduler.runNow("late.daily");

    assertEquals(2, store.history.size());
    assertEquals(List.of("cron", "manual"), store.history.stream().map(e -> e.trigger()).toList());
    assertEquals(List.of("scheduler:scheduler:late", "scheduler:scheduler:late"), model.sessionIds);
  }
}
