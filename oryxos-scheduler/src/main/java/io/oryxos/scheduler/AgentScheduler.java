package io.oryxos.scheduler;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.SafeErrorText;
import io.oryxos.core.agent.SessionIds;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.schedule.ProfileScheduleSync;
import io.oryxos.core.schedule.ScheduleConfigException;
import io.oryxos.core.schedule.ScheduleNotFoundException;
import io.oryxos.core.schedule.ScheduleService;
import io.oryxos.core.schedule.ScheduleUpdate;
import io.oryxos.core.schedule.ScheduleView;
import io.oryxos.core.schedule.ScheduledTask;
import io.oryxos.core.schedule.ScheduledTaskStore;
import io.oryxos.core.schedule.SchedulerDisabledException;
import io.oryxos.core.schedule.TaskAlreadyRunningException;
import io.oryxos.core.schedule.TaskExecution;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;

/**
 * Triggers, single-flight, status and history for the schedules declared in Profiles. It does no
 * agent work itself: every run goes through {@link AgentService#process} with the stable identity
 * {@code channel=scheduler, user=scheduler, profile=<profile>}, so the session id comes from the
 * same {@link SessionIds} as CLI and HTTP chats, and ReAct, memory, tools, sandbox and the audit
 * tables behave exactly as for a manual chat. A scheduled run therefore has no more permission than
 * the Profile's own sandbox declaration (default deny).
 *
 * <p>Single-instance only: the no-overlap lock lives in this JVM. Two application instances on the
 * same database would both fire; there is no distributed lock and no exactly-once guarantee.
 */
public final class AgentScheduler implements ScheduleService, ProfileScheduleSync, SmartLifecycle {
  public static final String CHANNEL = "scheduler";
  public static final String USER = "scheduler";

  private static final Logger log = LoggerFactory.getLogger(AgentScheduler.class);

  private record Definition(
      String taskId, String profileName, ScheduleConfig config, String hash) {}

  private final AgentService agent;
  private final Supplier<Collection<AgentProfile>> profiles;
  private final ScheduledTaskStore store;
  private final TaskScheduler taskScheduler;
  private final Clock clock;
  private final boolean enabled;
  // Insertion-ordered snapshot, replaced as a whole under the scheduler's monitor: readers never
  // lock and never see a half-changed map; triggers are registered in profile order.
  private volatile Map<String, Definition> definitions = Map.of();
  private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
  private final Set<String> running = ConcurrentHashMap.newKeySet();
  private volatile boolean started;

  /** Validates every schedule of every Profile; an invalid one throws, which stops startup. */
  public AgentScheduler(
      AgentService agent,
      Supplier<Collection<AgentProfile>> profiles,
      ScheduledTaskStore store,
      TaskScheduler taskScheduler,
      Clock clock,
      boolean enabled) {
    this.agent = Objects.requireNonNull(agent);
    this.profiles = Objects.requireNonNull(profiles);
    this.store = Objects.requireNonNull(store);
    this.taskScheduler = Objects.requireNonNull(taskScheduler);
    this.clock = Objects.requireNonNull(clock);
    this.enabled = enabled;
    Map<String, Definition> initial = new LinkedHashMap<>();
    for (AgentProfile profile : profiles.get()) {
      for (ScheduleConfig config : ScheduleConfigParser.parse(profile)) {
        String taskId = ScheduleConfigParser.taskId(profile.name(), config.id());
        initial.put(
            taskId, new Definition(taskId, profile.name(), config, hashOf(profile.name(), config)));
      }
    }
    definitions = Collections.unmodifiableMap(initial);
  }

  public boolean schedulerEnabled() {
    return enabled;
  }

  // ---- lifecycle -------------------------------------------------------------------------

  /** Registers every definition with the store and the trigger. Idempotent. */
  @Override
  public synchronized void start() {
    if (started) {
      return;
    }
    started = true;
    if (!enabled) {
      log.info("Scheduler is disabled (oryxos.scheduler.enabled=false): no schedule registered");
      return;
    }
    Map<String, AgentProfile> byName = new LinkedHashMap<>();
    for (AgentProfile profile : profiles.get()) {
      byName.put(profile.name(), profile);
    }
    for (Definition definition : List.copyOf(definitions.values())) {
      activate(definition, byName.get(definition.profileName()));
    }
  }

  /** Stores the definition (keeping runtime state) and registers its trigger. */
  private void activate(Definition definition, AgentProfile profile) {
    warnIfNoSandbox(profile, definition);
    ScheduledTask task =
        store.sync(
            definition.taskId(),
            definition.profileName(),
            definition.config().cron(),
            definition.config().zone().getId(),
            definition.config().message(),
            definition.hash());
    Instant next = task.enabled() ? nextRun(task.cron(), task.zone()) : null;
    store.updateNextRun(task.taskId(), next);
    register(task);
    log.info(
        "Registered schedule {} (cron='{}', zone={}, enabled={}, runs so far={})",
        task.taskId(),
        task.cron(),
        task.zone(),
        task.enabled(),
        task.runCount());
  }

  // ---- dynamic registration --------------------------------------------------------------

  /**
   * Makes the scheduler match the schedules of this Profile while the application runs. The
   * schedules are validated first, so an invalid one changes nothing. Schedules the Profile no
   * longer declares are stopped (their rows and history stay); an unchanged schedule is left alone,
   * a changed one has its old trigger cancelled before the new one is registered. A task the
   * operator disabled stays disabled, and run counts are never reset.
   */
  @Override
  public synchronized void registerProfile(AgentProfile profile) {
    Objects.requireNonNull(profile, "profile");
    Map<String, Definition> wanted = new LinkedHashMap<>();
    for (ScheduleConfig config : ScheduleConfigParser.parse(profile)) {
      String taskId = ScheduleConfigParser.taskId(profile.name(), config.id());
      wanted.put(
          taskId, new Definition(taskId, profile.name(), config, hashOf(profile.name(), config)));
    }
    for (Definition old : List.copyOf(definitions.values())) {
      if (old.profileName().equals(profile.name()) && !wanted.containsKey(old.taskId())) {
        forget(old.taskId());
        cancel(old.taskId());
      }
    }
    for (Definition definition : wanted.values()) {
      Definition existing = remember(definition);
      boolean unchanged = existing != null && existing.hash().equals(definition.hash());
      if (started && enabled && !(unchanged && futures.containsKey(definition.taskId()))) {
        activate(definition, profile);
      }
    }
  }

  @Override
  public synchronized void unregisterProfile(String profileName) {
    for (Definition definition : List.copyOf(definitions.values())) {
      if (definition.profileName().equals(profileName)) {
        forget(definition.taskId());
        cancel(definition.taskId());
      }
    }
  }

  /** Adds or replaces a definition; returns the one it replaced. Caller holds the monitor. */
  private Definition remember(Definition definition) {
    Map<String, Definition> next = new LinkedHashMap<>(definitions);
    Definition previous = next.put(definition.taskId(), definition);
    definitions = Collections.unmodifiableMap(next);
    return previous;
  }

  private void forget(String taskId) {
    Map<String, Definition> next = new LinkedHashMap<>(definitions);
    next.remove(taskId);
    definitions = Collections.unmodifiableMap(next);
  }

  private void cancel(String taskId) {
    ScheduledFuture<?> future = futures.remove(taskId);
    if (future != null) {
      future.cancel(false);
    }
  }

  @Override
  public synchronized void stop() {
    futures.values().forEach(future -> future.cancel(false));
    futures.clear();
    started = false;
  }

  @Override
  public boolean isRunning() {
    return started;
  }

  private void warnIfNoSandbox(AgentProfile profile, Definition definition) {
    if (profile != null
        && !profile.tools().isEmpty()
        && !profile.settings().containsKey("sandbox")) {
      log.warn(
          "Schedule {} runs profile '{}' with tools but no settings.sandbox: file, network and"
              + " shell tools will be denied (default deny)",
          definition.taskId(),
          definition.profileName());
    }
  }

  private void register(ScheduledTask task) {
    ScheduledFuture<?> previous = futures.remove(task.taskId());
    if (previous != null) {
      previous.cancel(false);
    }
    ScheduledFuture<?> future =
        taskScheduler.schedule(
            () -> fire(task.taskId()), new CronTrigger(task.cron(), ZoneId.of(task.zone())));
    if (future != null) {
      futures.put(task.taskId(), future);
    }
  }

  // ---- triggering ------------------------------------------------------------------------

  /** Cron entry point. Never throws: a failure here must not stop later firings. */
  public void fire(String taskId) {
    try {
      if (!definitions.containsKey(taskId)) {
        log.warn("Cron fired for schedule {} that is no longer registered: skipped", taskId);
        return;
      }
      ScheduledTask task = store.find(taskId).orElse(null);
      if (task == null) {
        log.warn("Cron fired for unknown schedule {}", taskId);
        return;
      }
      if (!task.enabled()) {
        log.debug("Schedule {} is disabled: skipped", taskId);
        return;
      }
      execute(task, TaskExecution.TRIGGER_CRON);
    } catch (TaskAlreadyRunningException e) {
      log.warn("Schedule {} is still running: this firing is skipped", taskId);
    } catch (RuntimeException e) {
      log.error("Schedule {} firing failed: {}", taskId, e.getClass().getSimpleName());
    }
  }

  @Override
  public TaskExecution runNow(String taskId) {
    requireEnabled();
    return execute(requireTask(taskId), TaskExecution.TRIGGER_MANUAL);
  }

  private TaskExecution execute(ScheduledTask task, String trigger) {
    String taskId = task.taskId();
    if (!running.add(taskId)) {
      throw new TaskAlreadyRunningException(taskId);
    }
    try {
      String sessionId = SessionIds.of(CHANNEL, USER, task.profileName());
      Instant started = clock.instant();
      long begun = System.nanoTime();
      boolean success = true;
      String error = null;
      try {
        agent.process(CHANNEL, USER, task.profileName(), task.message());
      } catch (RuntimeException e) {
        success = false;
        error = SafeErrorText.ofException("Agent run failed", e);
        log.warn("Schedule {} failed: {}", taskId, e.getClass().getSimpleName());
      }
      long millis = (System.nanoTime() - begun) / 1_000_000L;
      ScheduledTask latest = store.find(taskId).orElse(task);
      Instant next = latest.enabled() ? nextRun(latest.cron(), latest.zone()) : null;
      TaskExecution record =
          new TaskExecution(0, taskId, sessionId, trigger, started, success, error, millis);
      try {
        return store.recordExecution(record, next);
      } catch (RuntimeException e) {
        log.error("Could not record execution of {}: {}", taskId, e.getClass().getSimpleName());
        return record;
      }
    } finally {
      running.remove(taskId);
    }
  }

  // ---- ScheduleService -------------------------------------------------------------------

  @Override
  public List<ScheduleView> list() {
    List<ScheduledTask> tasks = new ArrayList<>();
    if (enabled) {
      for (String taskId : definitions.keySet()) {
        store.find(taskId).ifPresent(tasks::add);
      }
    } else {
      tasks.addAll(store.findAll());
    }
    tasks.sort(Comparator.comparing(ScheduledTask::taskId));
    return tasks.stream()
        .map(task -> ScheduleView.of(task, running.contains(task.taskId())))
        .toList();
  }

  @Override
  public List<TaskExecution> executions(String taskId, int limit) {
    if (!definitions.containsKey(taskId) && store.find(taskId).isEmpty()) {
      throw new ScheduleNotFoundException(taskId);
    }
    return store.executions(taskId, limit);
  }

  @Override
  public synchronized ScheduleView update(String taskId, ScheduleUpdate update) {
    requireEnabled();
    Objects.requireNonNull(update, "update");
    if (update.isEmpty()) {
      throw new ScheduleConfigException("nothing to update");
    }
    ScheduledTask existing = requireTask(taskId);
    String cron = update.cron() == null ? existing.cron() : update.cron().strip();
    String zone = update.zone() == null ? existing.zone() : update.zone().strip();
    ScheduleConfigParser.validate(cron, zone);
    if (update.message() != null) {
      ScheduleConfigParser.validateMessage(update.message());
    }
    boolean nowEnabled = update.enabled() == null ? existing.enabled() : update.enabled();
    Instant next = nowEnabled ? nextRun(cron, zone) : null;
    ScheduledTask saved =
        store.update(
            taskId,
            update.enabled(),
            update.cron() == null ? null : cron,
            update.zone() == null ? null : zone,
            update.message(),
            next);
    if (!cron.equals(existing.cron()) || !zone.equals(existing.zone())) {
      register(saved);
    }
    return ScheduleView.of(saved, running.contains(taskId));
  }

  private ScheduledTask requireTask(String taskId) {
    if (taskId == null || !definitions.containsKey(taskId)) {
      throw new ScheduleNotFoundException(String.valueOf(taskId));
    }
    return store.find(taskId).orElseThrow(() -> new ScheduleNotFoundException(taskId));
  }

  private void requireEnabled() {
    if (!enabled) {
      throw new SchedulerDisabledException();
    }
  }

  // ---- helpers ---------------------------------------------------------------------------

  private Instant nextRun(String cron, String zone) {
    ZonedDateTime next =
        CronExpression.parse(cron).next(ZonedDateTime.ofInstant(clock.instant(), ZoneId.of(zone)));
    return next == null ? null : next.toInstant();
  }

  private static String hashOf(String profileName, ScheduleConfig config) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String part :
          List.of(
              profileName, config.id(), config.cron(), config.zone().getId(), config.message())) {
        digest.update(part.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JDK", e);
    }
  }
}
