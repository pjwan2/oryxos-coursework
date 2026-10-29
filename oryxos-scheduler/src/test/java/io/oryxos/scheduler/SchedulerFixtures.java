package io.oryxos.scheduler;

import io.oryxos.core.agent.AgentMessage;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.AgentSession;
import io.oryxos.core.agent.AgentSessionStore;
import io.oryxos.core.agent.AgentToolExecutor;
import io.oryxos.core.agent.ModelGateway;
import io.oryxos.core.agent.ModelTurn;
import io.oryxos.core.agent.PromptBuilder;
import io.oryxos.core.agent.ReActLoop;
import io.oryxos.core.agent.ToolAction;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.schedule.ScheduledTask;
import io.oryxos.core.schedule.ScheduledTaskStore;
import io.oryxos.core.schedule.TaskExecution;
import io.oryxos.core.tool.ToolSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/** Hand-made collaborators: no Spring context, no database, no real clock or threads. */
final class SchedulerFixtures {
  static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private SchedulerFixtures() {}

  static AgentProfile profile(String name, List<Map<String, Object>> schedules) {
    return new AgentProfile(
        name,
        "test profile",
        Map.of(),
        new AgentProfile.ProviderSettings("mock", "m", 0.2),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        schedules,
        Map.of(),
        Map.of());
  }

  static Map<String, Object> schedule(String id, String cron, String zone, String message) {
    return Map.of("id", id, "cron", cron, "zone", zone, "message", message);
  }

  /** Real AgentService / ReActLoop over a scripted model and an in-memory session store. */
  static AgentService agentService(
      ModelGateway model, List<AgentProfile> profiles, InMemorySessions sessions) {
    Map<String, AgentProfile> byName = new LinkedHashMap<>();
    profiles.forEach(p -> byName.put(p.name(), p));
    AgentToolExecutor noTools =
        new AgentToolExecutor() {
          @Override
          public List<ToolSpec> allowedSpecifications(AgentProfile profile) {
            return List.of();
          }

          @Override
          public ToolOutput execute(String sessionId, AgentProfile profile, ToolAction action) {
            return ToolOutput.failed("no tools in this fixture", false);
          }
        };
    ReActLoop loop = new ReActLoop(model, noTools, new PromptBuilder(CLOCK, 20));
    return new AgentService(
        loop,
        name -> {
          AgentProfile profile = byName.get(name);
          if (profile == null) {
            throw new IllegalArgumentException("Unknown agent profile: " + name);
          }
          return profile;
        },
        sessions);
  }

  /** Answers "done" and remembers which sessions asked. */
  static final class RecordingModel implements ModelGateway {
    final List<String> sessionIds = new CopyOnWriteArrayList<>();

    @Override
    public ModelTurn chat(
        String sessionId, AgentProfile profile, List<AgentMessage> prompt, List<ToolSpec> tools) {
      sessionIds.add(sessionId);
      return ModelTurn.finalAnswer("done");
    }
  }

  static final class InMemorySessions implements AgentSessionStore {
    final Map<String, AgentSession> sessions = new LinkedHashMap<>();

    @Override
    public synchronized AgentSession loadOrCreate(
        String channel, String userId, String profileName) {
      String id = io.oryxos.core.agent.SessionIds.of(channel, userId, profileName);
      return sessions.computeIfAbsent(id, key -> new AgentSession(key, profileName));
    }

    @Override
    public synchronized void save(AgentSession session) {
      sessions.put(session.id(), session);
    }

    @Override
    public synchronized Optional<AgentSession> find(String sessionId) {
      return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public synchronized List<io.oryxos.core.agent.SessionSummary> list(int limit) {
      return List.of();
    }

    @Override
    public synchronized boolean discard(String sessionId) {
      return sessions.remove(sessionId) != null;
    }

    @Override
    public synchronized long count() {
      return sessions.size();
    }
  }

  static final class InMemoryTaskStore implements ScheduledTaskStore {
    final Map<String, ScheduledTask> tasks = new LinkedHashMap<>();
    final Map<String, String> hashes = new LinkedHashMap<>();
    final List<TaskExecution> history = new ArrayList<>();
    volatile boolean failRecording;

    @Override
    public synchronized ScheduledTask sync(
        String taskId,
        String profileName,
        String cron,
        String zone,
        String message,
        String definitionHash) {
      ScheduledTask old = tasks.get(taskId);
      if (old == null) {
        tasks.put(
            taskId,
            new ScheduledTask(taskId, profileName, cron, zone, message, true, null, null, null, 0));
      } else if (!definitionHash.equals(hashes.get(taskId))) {
        tasks.put(
            taskId,
            new ScheduledTask(
                taskId,
                profileName,
                cron,
                zone,
                message,
                old.enabled(),
                old.nextRunAt(),
                old.lastRunAt(),
                old.lastStatus(),
                old.runCount()));
      }
      hashes.put(taskId, definitionHash);
      return tasks.get(taskId);
    }

    @Override
    public synchronized Optional<ScheduledTask> find(String taskId) {
      return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public synchronized List<ScheduledTask> findAll() {
      return tasks.values().stream().sorted(Comparator.comparing(ScheduledTask::taskId)).toList();
    }

    @Override
    public synchronized ScheduledTask update(
        String taskId,
        Boolean enabled,
        String cron,
        String zone,
        String message,
        Instant nextRunAt) {
      ScheduledTask t = tasks.get(taskId);
      ScheduledTask changed =
          new ScheduledTask(
              taskId,
              t.profileName(),
              cron == null ? t.cron() : cron,
              zone == null ? t.zone() : zone,
              message == null ? t.message() : message,
              enabled == null ? t.enabled() : enabled,
              nextRunAt,
              t.lastRunAt(),
              t.lastStatus(),
              t.runCount());
      tasks.put(taskId, changed);
      return changed;
    }

    @Override
    public synchronized void updateNextRun(String taskId, Instant nextRunAt) {
      ScheduledTask t = tasks.get(taskId);
      tasks.put(
          taskId,
          new ScheduledTask(
              taskId,
              t.profileName(),
              t.cron(),
              t.zone(),
              t.message(),
              t.enabled(),
              nextRunAt,
              t.lastRunAt(),
              t.lastStatus(),
              t.runCount()));
    }

    @Override
    public synchronized TaskExecution recordExecution(TaskExecution e, Instant nextRunAt) {
      if (failRecording) {
        throw new IllegalStateException("database is down");
      }
      TaskExecution saved =
          new TaskExecution(
              history.size() + 1L,
              e.taskId(),
              e.sessionId(),
              e.trigger(),
              e.startedAt(),
              e.success(),
              e.errorMessage(),
              e.durationMs());
      history.add(saved);
      ScheduledTask t = tasks.get(e.taskId());
      tasks.put(
          e.taskId(),
          new ScheduledTask(
              t.taskId(),
              t.profileName(),
              t.cron(),
              t.zone(),
              t.message(),
              t.enabled(),
              nextRunAt,
              e.startedAt(),
              e.success() ? ScheduledTask.STATUS_SUCCESS : ScheduledTask.STATUS_FAILED,
              t.runCount() + 1));
      return saved;
    }

    @Override
    public synchronized List<TaskExecution> executions(String taskId, int limit) {
      List<TaskExecution> mine =
          new ArrayList<>(history.stream().filter(e -> e.taskId().equals(taskId)).toList());
      java.util.Collections.reverse(mine);
      return mine.stream().limit(limit).toList();
    }
  }

  /** Captures what would be scheduled; tests call the runnable themselves. */
  static final class RecordingTaskScheduler implements TaskScheduler {
    record Registration(Runnable task, Trigger trigger, StubFuture future) {}

    final List<Registration> registrations = new CopyOnWriteArrayList<>();

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
      StubFuture future = new StubFuture();
      registrations.add(new Registration(task, trigger, future));
      return future;
    }

    Registration last() {
      return registrations.getLast();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable task, Instant startTime, java.time.Duration period) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, java.time.Duration period) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable task, Instant startTime, java.time.Duration delay) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, java.time.Duration delay) {
      throw new UnsupportedOperationException();
    }
  }

  static final class StubFuture implements ScheduledFuture<Object> {
    final AtomicBoolean cancelled = new AtomicBoolean();

    @Override
    public long getDelay(TimeUnit unit) {
      return 0;
    }

    @Override
    public int compareTo(Delayed other) {
      return 0;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return cancelled.compareAndSet(false, true);
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    @Override
    public boolean isDone() {
      return cancelled.get();
    }

    @Override
    public Object get() {
      return null;
    }

    @Override
    public Object get(long timeout, TimeUnit unit) {
      return null;
    }
  }
}
