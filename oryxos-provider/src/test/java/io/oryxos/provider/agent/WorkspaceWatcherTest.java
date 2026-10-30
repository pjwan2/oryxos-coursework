package io.oryxos.provider.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.provider.ProfileRegistry;
import io.oryxos.provider.agent.AgentFixtures.RecordingSync;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real file-system events (the JDK WatchService, which on Windows is ReadDirectoryChangesW). The
 * waits are polling loops with a generous ceiling, never fixed sleeps, so the tests are repeatable.
 */
class WorkspaceWatcherTest {
  private static final Duration DEBOUNCE = Duration.ofMillis(150);

  @TempDir Path temp;

  private Path root;
  private ProfileRegistry registry;
  private RecordingSync sync;
  private AgentLifecycleService lifecycle;
  private WorkspaceWatcher watcher;

  private static String agentMd(String description, String body) {
    return "---\ndescription: "
        + description
        + "\nprovider: {name: mock, model: m}\ntools: [read_file]\n---\n"
        + body
        + "\n";
  }

  @BeforeEach
  void setUp() throws IOException {
    Path profiles = Files.createDirectories(temp.resolve("profiles"));
    root = Files.createDirectories(temp.resolve("agents"));
    AgentLoader loader = AgentFixtures.loader(profiles);
    AgentDirectoryScanner scanner = new AgentDirectoryScanner(root, loader);
    AgentStartup.Report startup = AgentStartup.load(AgentFixtures.profiles(profiles), scanner);
    registry = new ProfileRegistry(startup.profiles());
    sync = new RecordingSync();
    lifecycle = new AgentLifecycleService(registry, scanner, loader, sync, startup);
    watcher = new WorkspaceWatcher(lifecycle, DEBOUNCE, Duration.ofHours(1));
    watcher.start();
  }

  @AfterEach
  void tearDown() {
    watcher.close();
  }

  private static void eventually(String what, BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("Timed out waiting for: " + what);
  }

  private void write(String name, String text) throws IOException {
    Path dir = Files.createDirectories(root.resolve(name));
    Files.writeString(dir.resolve("AGENT.md"), text);
  }

  @Test
  void aDirectoryCopiedInBecomesAnAgentWithoutARestart() throws Exception {
    write("copied", agentMd("copied in", "Body"));

    eventually("copied registers", () -> registry.exists("copied"));

    assertEquals("copied in", registry.get("copied").description());
    assertTrue(lifecycle.definition("copied").isPresent());
  }

  @Test
  void editingAgentMdReloadsTheAgentAndRegistersTheNewSchedule() throws Exception {
    write("editor", agentMd("v1", "Body one"));
    eventually("v1", () -> registry.exists("editor"));

    write("editor", agentMd("v2", "Body two"));

    eventually("v2 loaded", () -> "v2".equals(registry.get("editor").description()));
    assertTrue(registry.get("editor").identity().get("prompt").toString().contains("Body two"));
    assertFalse(registry.get("editor").identity().get("prompt").toString().contains("Body one"));
  }

  @Test
  void deletingTheDirectoryRetiresTheAgentAndStopsItsSchedules() throws Exception {
    write("doomed", agentMd("doomed", "Body"));
    eventually("registered", () -> registry.exists("doomed"));

    Files.delete(root.resolve("doomed/AGENT.md"));
    Files.delete(root.resolve("doomed"));

    eventually("retired", () -> !registry.exists("doomed"));
    eventually("scheduler told", () -> sync.calls.contains("unregister:doomed"));
  }

  @Test
  void anInvalidEditKeepsTheOldAgentAndTheNextGoodSaveFixesIt() throws Exception {
    write("fragile", agentMd("good", "Body"));
    eventually("registered", () -> registry.exists("fragile"));

    write("fragile", "---\ndescription: [unclosed\n---\nBody");
    eventually("problem recorded", () -> lifecycle.problems().containsKey("fragile"));
    assertEquals("good", registry.get("fragile").description(), "the valid agent keeps running");

    write("fragile", agentMd("good again", "Body"));
    eventually("fixed", () -> "good again".equals(registry.get("fragile").description()));
    eventually("problem cleared", () -> !lifecycle.problems().containsKey("fragile"));
  }

  @Test
  void aBadDirectoryDoesNotStopTheOthersFromLoading() throws Exception {
    write("bad", "no frontmatter at all");
    write("good", agentMd("good", "Body"));

    eventually("good registers", () -> registry.exists("good"));

    assertFalse(registry.exists("bad"));
    eventually("bad reported", () -> lifecycle.problems().containsKey("bad"));
  }

  @Test
  void aBurstOfWritesBecomesOneReloadOfTheFinalContent() throws Exception {
    write("busy", agentMd("v0", "Body"));
    eventually("registered", () -> registry.exists("busy"));
    sync.calls.clear();

    for (int i = 1; i <= 30; i++) {
      Files.writeString(root.resolve("busy/AGENT.md"), agentMd("v" + i, "Body " + i));
    }

    eventually("final content", () -> "v30".equals(registry.get("busy").description()));
    Thread.sleep(DEBOUNCE.toMillis() * 3);
    assertTrue(
        sync.calls.stream().filter("register:busy"::equals).count() <= 3,
        "30 writes did not cause 30 reloads: " + sync.calls);
  }

  @Test
  void aFileThatIsStillBeingWrittenIsNeverAppliedHalfWay() throws Exception {
    String full = agentMd("whole", "Body of the finished file");
    String firstHalf = full.substring(0, full.length() / 2);
    Path dir = Files.createDirectories(root.resolve("slow"));

    Files.writeString(dir.resolve("AGENT.md"), firstHalf); // an unfinished frontmatter
    Thread.sleep(DEBOUNCE.toMillis() / 3); // quieter than the debounce: still "being written"
    assertFalse(registry.exists("slow"), "nothing is applied while writes keep coming");
    Files.writeString(dir.resolve("AGENT.md"), full);

    eventually("complete file applied", () -> registry.exists("slow"));
    assertEquals("whole", registry.get("slow").description());
  }

  @Test
  void anEditorStyleAtomicReplaceIsPickedUp() throws Exception {
    write("atomic", agentMd("before", "Body"));
    eventually("registered", () -> registry.exists("atomic"));

    Path temporary = root.resolve("atomic/.AGENT.md.swp");
    Files.writeString(temporary, agentMd("after atomic replace", "Body"));
    Files.move(
        temporary,
        root.resolve("atomic/AGENT.md"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);

    eventually(
        "replaced", () -> "after atomic replace".equals(registry.get("atomic").description()));
  }

  @Test
  void reconcileCatchesWhatNoEventDeliveredAndAClosedWatcherStopsReacting() throws Exception {
    watcher.close();
    write("late", agentMd("late", "Body"));
    Thread.sleep(DEBOUNCE.toMillis() * 2);
    assertFalse(registry.exists("late"), "a closed watcher does nothing");

    lifecycle.reconcile();

    assertTrue(registry.exists("late"), "the periodic reconcile is the safety net");
  }

  @Test
  void aSecondWatcherCanStartAfterTheFirstIsClosed() throws Exception {
    watcher.close();
    WorkspaceWatcher again = new WorkspaceWatcher(lifecycle, DEBOUNCE, Duration.ofHours(1));
    again.start();
    try {
      write("reborn", agentMd("reborn", "Body"));
      eventually("registered by the second watcher", () -> registry.exists("reborn"));
    } finally {
      again.close();
    }
  }
}
