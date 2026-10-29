package io.oryxos.scheduler;

import static io.oryxos.scheduler.SchedulerFixtures.CLOCK;
import static io.oryxos.scheduler.SchedulerFixtures.profile;
import static io.oryxos.scheduler.SchedulerFixtures.schedule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.AgentMessage;
import io.oryxos.core.agent.ModelGateway;
import io.oryxos.core.agent.ModelTurn;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.schedule.TaskExecution;
import io.oryxos.core.tool.ToolSpec;
import io.oryxos.scheduler.SchedulerFixtures.InMemorySessions;
import io.oryxos.scheduler.SchedulerFixtures.InMemoryTaskStore;
import io.oryxos.scheduler.SchedulerFixtures.RecordingTaskScheduler;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Two agents share one scheduler; nothing that happens to one may leak into the other. */
class MultiAgentSchedulerIsolationTest {
  private final InMemoryTaskStore store = new InMemoryTaskStore();
  private final InMemorySessions sessions = new InMemorySessions();
  private final RecordingTaskScheduler triggers = new RecordingTaskScheduler();
  private final ExecutorService pool = Executors.newCachedThreadPool();

  @AfterEach
  void shutDown() {
    pool.shutdownNow();
  }

  private static final AgentProfile A =
      profile("agent-a", List.of(schedule("daily", "0 0 9 * * *", "UTC", "question for a")));
  private static final AgentProfile B =
      profile("agent-b", List.of(schedule("daily", "0 0 9 * * *", "UTC", "question for b")));

  /** Fails for agent-a, can be held up for agent-b, otherwise answers. */
  private static final class PerAgentModel implements ModelGateway {
    final CountDownLatch bEntered = new CountDownLatch(1);
    final CountDownLatch bRelease = new CountDownLatch(1);
    final boolean holdB;

    PerAgentModel(boolean holdB) {
      this.holdB = holdB;
    }

    @Override
    public ModelTurn chat(
        String sessionId, AgentProfile profile, List<AgentMessage> prompt, List<ToolSpec> tools) {
      if (profile.name().equals("agent-a")) {
        throw new IllegalStateException("agent-a is broken");
      }
      if (holdB) {
        bEntered.countDown();
        try {
          if (!bRelease.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("test timeout");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
      return ModelTurn.finalAnswer("b is fine");
    }
  }

  private AgentScheduler start(PerAgentModel model) {
    AgentScheduler scheduler =
        new AgentScheduler(
            SchedulerFixtures.agentService(model, List.of(A, B), sessions),
            () -> List.of(A, B),
            store,
            triggers,
            CLOCK,
            true);
    scheduler.start();
    return scheduler;
  }

  @Test
  void aFailureInAgentADoesNotAffectAgentBsTaskOrHistory() {
    AgentScheduler scheduler = start(new PerAgentModel(false));

    triggers.registrations.get(0).task().run(); // agent-a: fails
    triggers.registrations.get(1).task().run(); // agent-b: succeeds
    triggers.registrations.get(0).task().run(); // agent-a again: still just fails

    assertEquals("FAILED", store.tasks.get("agent-a.daily").lastStatus());
    assertEquals(2, store.tasks.get("agent-a.daily").runCount());
    assertEquals("SUCCESS", store.tasks.get("agent-b.daily").lastStatus());
    assertEquals(1, store.tasks.get("agent-b.daily").runCount());
    assertTrue(scheduler.executions("agent-b.daily", 10).stream().allMatch(TaskExecution::success));
    assertTrue(
        scheduler.executions("agent-a.daily", 10).stream().noneMatch(TaskExecution::success));
  }

  @Test
  void eachAgentUsesItsOwnSessionAndItsOwnMessage() {
    start(new PerAgentModel(false));
    triggers.registrations.get(1).task().run();
    triggers.registrations.get(0).task().run();

    assertEquals(
        List.of("scheduler:scheduler:agent-a", "scheduler:scheduler:agent-b"),
        sessions.sessions.keySet().stream().sorted().toList());
    assertEquals(
        "question for b",
        sessions.sessions.get("scheduler:scheduler:agent-b").transcript().getFirst().content());
    assertEquals(
        "question for a",
        sessions.sessions.get("scheduler:scheduler:agent-a").transcript().getFirst().content());
  }

  @Test
  void aLongRunByAgentBDoesNotBlockAgentAAndDisablingOneLeavesTheOtherAlone() throws Exception {
    PerAgentModel model = new PerAgentModel(true);
    AgentScheduler scheduler = start(model);

    Future<?> b = pool.submit(() -> triggers.registrations.get(1).task().run());
    assertTrue(model.bEntered.await(5, TimeUnit.SECONDS));

    triggers.registrations.get(0).task().run(); // agent-a completes (as a failure) while b runs
    assertEquals(1, store.tasks.get("agent-a.daily").runCount());
    assertTrue(
        scheduler.list().stream()
            .filter(v -> v.taskId().equals("agent-b.daily"))
            .findFirst()
            .orElseThrow()
            .running());
    assertFalse(
        scheduler.list().stream()
            .filter(v -> v.taskId().equals("agent-a.daily"))
            .findFirst()
            .orElseThrow()
            .running());

    scheduler.update(
        "agent-a.daily", new io.oryxos.core.schedule.ScheduleUpdate(false, null, null, null));
    assertTrue(store.tasks.get("agent-b.daily").enabled());

    model.bRelease.countDown();
    b.get(5, TimeUnit.SECONDS);
    assertEquals("SUCCESS", store.tasks.get("agent-b.daily").lastStatus());
  }
}
