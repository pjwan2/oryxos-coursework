package io.oryxos.provider.agent;

import io.oryxos.core.profile.AgentConflictException;
import io.oryxos.core.profile.AgentDefinitionException;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the registry in step with the agents directory while the application runs: a directory
 * copied in becomes an agent, an edited AGENT.md is reloaded, a deleted directory retires its
 * agent. Rules that make this safe on every platform, Windows included:
 *
 * <ul>
 *   <li><b>Debounce.</b> A change is applied only after the directory has been quiet for {@code
 *       debounce}; a burst of events (an editor's temp file, a copy in progress) becomes one
 *       reload, and a half-written AGENT.md is never read while it is still being written.
 *   <li><b>Invalid means no change.</b> If the files do not validate, the previously registered
 *       agent keeps running and the reason is recorded; the next good save fixes it.
 *   <li><b>One bad directory affects only itself.</b>
 *   <li><b>Reconcile.</b> Every {@code reconcileEvery} the registry is compared with the disk, so
 *       an event the operating system dropped (or an overflow) is still picked up.
 * </ul>
 */
public final class WorkspaceWatcher implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(WorkspaceWatcher.class);

  private final AgentLifecycleService lifecycle;
  private final Path root;
  private final Duration debounce;
  private final Duration reconcileEvery;
  private final Map<String, Long> pending = new ConcurrentHashMap<>();
  private final Map<WatchKey, Path> keys = new HashMap<>();
  private volatile boolean running;
  private volatile boolean rootWatched;
  private volatile WatchService service;
  private Thread thread;

  public WorkspaceWatcher(
      AgentLifecycleService lifecycle, Duration debounce, Duration reconcileEvery) {
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.root = lifecycle.root();
    this.debounce = Objects.requireNonNull(debounce, "debounce");
    this.reconcileEvery = Objects.requireNonNull(reconcileEvery, "reconcileEvery");
  }

  public synchronized void start() throws IOException {
    if (running) {
      return;
    }
    service = FileSystems.getDefault().newWatchService();
    watchRootIfPresent();
    running = true;
    thread = new Thread(this::loop, "oryxos-agent-watcher");
    thread.setDaemon(true);
    thread.start();
  }

  @Override
  public synchronized void close() {
    running = false;
    try {
      if (service != null) {
        service.close();
      }
    } catch (IOException e) {
      LOG.warn("Closing the watcher failed: {}", e.toString());
    }
    if (thread != null) {
      thread.interrupt();
      try {
        thread.join(5000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Starts watching once the agents directory exists (it is not created just to be watched, so an
   * installation without agents leaves no trace). Until then the periodic reconcile covers it.
   */
  private void watchRootIfPresent() {
    if (rootWatched || !Files.isDirectory(root)) {
      return;
    }
    watch(root);
    try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
      for (Path child : children) {
        if (Files.isDirectory(child) && !AgentLoader.nameOf(child).startsWith(".")) {
          watch(child);
        }
      }
    } catch (IOException e) {
      LOG.warn("Cannot list {}: {}", root.getFileName(), e.toString());
    }
    rootWatched = true;
    LOG.info("Watching {} for agent changes", root);
  }

  private void watch(Path dir) {
    try {
      WatchKey key =
          dir.register(
              service,
              StandardWatchEventKinds.ENTRY_CREATE,
              StandardWatchEventKinds.ENTRY_MODIFY,
              StandardWatchEventKinds.ENTRY_DELETE);
      synchronized (keys) {
        keys.put(key, dir);
      }
    } catch (IOException e) {
      LOG.warn("Cannot watch {}: {}", dir.getFileName(), e.toString());
    }
  }

  private void loop() {
    long lastReconcile = System.nanoTime();
    while (running) {
      try {
        watchRootIfPresent();
        WatchKey key = service.poll(debounce.toMillis() / 2 + 1, TimeUnit.MILLISECONDS);
        if (key != null) {
          collect(key);
        }
        applyDue();
        if (System.nanoTime() - lastReconcile > reconcileEvery.toNanos()) {
          lastReconcile = System.nanoTime();
          lifecycle.reconcile();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (ClosedWatchServiceException closed) {
        return;
      } catch (RuntimeException unexpected) {
        LOG.error("Agent watcher continues after: {}", unexpected.toString());
      }
    }
  }

  private void collect(WatchKey key) {
    Path dir;
    synchronized (keys) {
      dir = keys.get(key);
    }
    long now = System.nanoTime();
    for (WatchEvent<?> event : key.pollEvents()) {
      if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
        pending.put("*", now);
        continue;
      }
      Path child = dir == null ? null : dir.resolve((Path) event.context());
      if (child == null) {
        continue;
      }
      String agent = dir.equals(root) ? AgentLoader.nameOf(child) : AgentLoader.nameOf(dir);
      if (agent.startsWith(".")) {
        continue;
      }
      if (dir.equals(root)
          && event.kind() == StandardWatchEventKinds.ENTRY_CREATE
          && Files.isDirectory(child)) {
        watch(child);
      }
      pending.put(agent, now);
    }
    if (!key.reset()) {
      synchronized (keys) {
        keys.remove(key);
      }
    }
  }

  /** Applies every name that has been quiet for the debounce time. */
  void applyDue() {
    long now = System.nanoTime();
    for (Map.Entry<String, Long> entry : Map.copyOf(pending).entrySet()) {
      if (now - entry.getValue() < debounce.toNanos()) {
        continue;
      }
      if (!pending.remove(entry.getKey(), entry.getValue())) {
        continue; // changed again meanwhile: wait for it to settle
      }
      if ("*".equals(entry.getKey())) {
        lifecycle.reconcile();
      } else {
        apply(entry.getKey());
      }
    }
  }

  /** The one decision: what should the registry look like for this directory name now? */
  void apply(String name) {
    Path dir = lifecycle.directoryOf(name);
    try {
      if (!Files.isDirectory(dir)) {
        if (lifecycle.unregister(name)) {
          LOG.info("Agent '{}' removed: its directory is gone", name);
        }
        lifecycle.clearProblem(name);
        return;
      }
      if (Files.isDirectory(dir)
          && !Files.exists(dir.resolve(AgentLoader.FILE_NAME))
          && lifecycle.definition(name).isEmpty()) {
        // Still being copied in (no AGENT.md yet): the next event will bring it.
        return;
      }
      lifecycle.register(dir);
    } catch (AgentDefinitionException | AgentConflictException refused) {
      LOG.warn("Agent '{}' not applied: {}", name, refused.getMessage());
    } catch (RuntimeException failure) {
      LOG.error("Agent '{}' could not be applied: {}", name, failure.toString());
      lifecycle.recordProblem(
          name, "The agent could not be applied (" + failure.getClass().getSimpleName() + ")");
    }
  }
}
