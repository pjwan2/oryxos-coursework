package io.oryxos.provider.agent;

import static io.oryxos.provider.agent.AgentFixtures.DAILY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.ProfileNotFoundException;
import io.oryxos.core.profile.AgentConflictException;
import io.oryxos.core.profile.AgentDefinitionException;
import io.oryxos.provider.ProfileRegistry;
import io.oryxos.provider.agent.AgentFixtures.RecordingSync;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Startup scan, copied directories and runtime registration all end in the same registry. */
class AgentScanRegisterTest {
  @TempDir Path temp;

  private Path profilesDir;
  private Path root;
  private AgentLoader loader;
  private AgentDirectoryScanner scanner;
  private RecordingSync sync;
  private ProfileRegistry registry;

  private static final String SIMPLE =
      "---\ndescription: simple\nprovider: {name: mock, model: m}\ntools: [read_file]\n---\nDo it.\n";

  @BeforeEach
  void setUp() throws IOException {
    profilesDir = temp.resolve("profiles");
    Files.createDirectories(profilesDir);
    root = temp.resolve("agents");
    loader = AgentFixtures.loader(profilesDir);
    scanner = new AgentDirectoryScanner(root, loader);
    sync = new RecordingSync();
  }

  private AgentLifecycleService lifecycle(ProfileRegistry registry, AgentStartup.Report startup) {
    return new AgentLifecycleService(registry, scanner, loader, sync, startup);
  }

  private AgentLifecycleService startup() {
    AgentStartup.Report report = AgentStartup.load(AgentFixtures.profiles(profilesDir), scanner);
    registry = new ProfileRegistry(report.profiles());
    return lifecycle(registry, report);
  }

  private ProfileRegistry registryOf(AgentLifecycleService service) {
    return registry;
  }

  @Test
  void theScannerLoadsValidDirectoriesAndReportsTheBrokenOnesWithoutStoppingAtThem()
      throws IOException {
    AgentFixtures.write(root, "daily-reconcile", DAILY);
    AgentFixtures.write(root, "simple", SIMPLE);
    AgentFixtures.write(root, "broken", "no frontmatter at all");
    Files.createDirectories(root.resolve("empty-dir"));
    Files.writeString(root.resolve("README.txt"), "not an agent");
    Files.createDirectories(root.resolve(".hidden"));

    AgentDirectoryScanner.Result result = scanner.scan();

    assertEquals(
        List.of("daily-reconcile", "simple"),
        result.agents().stream().map(AgentDefinition::name).toList());
    assertEquals(List.of("broken", "empty-dir"), List.copyOf(result.problems().keySet()));
    assertTrue(result.problems().get("broken").contains("must start with"));
    assertTrue(result.problems().get("empty-dir").contains("not found"));
  }

  @Test
  void aMissingAgentsDirectoryIsNotAnError() {
    assertEquals(List.of(), scanner.scan().agents());
    assertEquals(Map.of(), scanner.scan().problems());
  }

  @Test
  void startupRegistersLegacyYamlAndAgentMdTogether() throws IOException {
    Files.writeString(
        profilesDir.resolve("legacy.yaml"),
        "name: legacy\ndescription: old\nprovider: {name: mock, model: m}\n");
    AgentFixtures.write(root, "simple", SIMPLE);

    AgentStartup.Report report = AgentStartup.load(AgentFixtures.profiles(profilesDir), scanner);

    assertEquals(
        List.of("legacy", "simple"),
        List.copyOf(new java.util.TreeSet<>(report.profiles().keySet())));
    assertEquals(List.of("legacy"), List.copyOf(report.legacy()));
    assertEquals(List.of("simple"), List.copyOf(report.agents().keySet()));
  }

  @Test
  void theSameNameInYamlAndAgentMdIsADeterministicConflictAndTheLegacyOneStays()
      throws IOException {
    Files.writeString(
        profilesDir.resolve("simple.yaml"),
        "name: simple\ndescription: LEGACY\nprovider: {name: mock, model: m}\n");
    AgentFixtures.write(root, "simple", SIMPLE);

    for (int run = 0; run < 3; run++) {
      AgentStartup.Report report = AgentStartup.load(AgentFixtures.profiles(profilesDir), scanner);
      assertEquals("LEGACY", report.profiles().get("simple").description(), "run " + run);
      assertFalse(report.agents().containsKey("simple"));
      assertEquals(AgentStartup.conflictMessage("simple"), report.problems().get("simple"));
    }
    assertTrue(AgentStartup.conflictMessage("simple").contains("Rename the agent or remove one"));
  }

  @Test
  void aCopiedDirectoryCanBeRegisteredWhileTheApplicationRuns() {
    AgentLifecycleService service = startup();
    assertFalse(registry.exists("daily-reconcile"));

    Path dir = AgentFixtures.write(root, "daily-reconcile", DAILY);
    AgentDefinition definition = service.register(dir);

    assertEquals("daily-reconcile", definition.name());
    assertTrue(registry.exists("daily-reconcile"));
    assertEquals(List.of("register:daily-reconcile"), sync.calls);
    assertTrue(service.definition("daily-reconcile").isPresent());
    assertFalse(service.isLegacy("daily-reconcile"));
  }

  @Test
  void registeringTwiceIsAReloadNotAnError() {
    AgentLifecycleService service = startup();
    Path dir = AgentFixtures.write(root, "simple", SIMPLE);
    service.register(dir);

    AgentFixtures.write(root, "simple", SIMPLE.replace("simple\n", "changed\n"));
    service.register(dir);

    assertEquals("changed", registryOf(service).get("simple").description());
    assertEquals(List.of("register:simple", "register:simple"), sync.calls);
  }

  @Test
  void anInvalidEditKeepsThePreviousValidAgentRunning() {
    AgentLifecycleService service = startup();
    Path dir = AgentFixtures.write(root, "simple", SIMPLE);
    service.register(dir);

    AgentFixtures.write(root, "simple", "---\ndescription: [oops\n---\nBody");
    AgentDefinitionException problem =
        assertThrows(AgentDefinitionException.class, () -> service.register(dir));

    assertTrue(problem.getMessage().contains("not valid YAML"));
    assertEquals(
        "simple", registryOf(service).get("simple").description(), "the old agent is untouched");
    assertTrue(service.problems().get("simple").contains("not valid YAML"));

    AgentFixtures.write(root, "simple", SIMPLE);
    service.register(dir);
    assertFalse(service.problems().containsKey("simple"), "a good reload clears the problem");
  }

  @Test
  void aSchedulerFailureRollsTheRegistrationBack() {
    AgentLifecycleService service = startup();
    Path dir = AgentFixtures.write(root, "simple", SIMPLE);
    sync.failOnRegister = new IllegalStateException("scheduler is broken");

    assertThrows(IllegalStateException.class, () -> service.register(dir));

    assertFalse(registry.exists("simple"), "no half-registered agent");
    assertTrue(service.definition("simple").isEmpty());
  }

  @Test
  void aSchedulerFailureDuringAReloadRestoresTheOldDefinition() {
    AgentLifecycleService service = startup();
    Path dir = AgentFixtures.write(root, "simple", SIMPLE);
    service.register(dir);
    sync.failOnRegister = new IllegalStateException("scheduler is broken");
    AgentFixtures.write(root, "simple", SIMPLE.replace("simple\n", "new text\n"));

    assertThrows(IllegalStateException.class, () -> service.register(dir));
    sync.failOnRegister = null;

    assertEquals("simple", registryOf(service).get("simple").description());
    assertEquals("simple", service.definition("simple").orElseThrow().profile().description());
  }

  @Test
  void aDirectoryWhoseNameBelongsToALegacyProfileIsRefusedAtRuntimeToo() throws IOException {
    Files.writeString(
        profilesDir.resolve("simple.yaml"),
        "name: simple\ndescription: LEGACY\nprovider: {name: mock, model: m}\n");
    AgentLifecycleService service = startup();
    Path dir = AgentFixtures.write(root, "simple", SIMPLE);

    AgentConflictException conflict =
        assertThrows(AgentConflictException.class, () -> service.register(dir));

    assertEquals(AgentStartup.conflictMessage("simple"), conflict.getMessage());
    assertEquals("LEGACY", registryOf(service).get("simple").description());
    assertTrue(service.isLegacy("simple"));
  }

  @Test
  void unregisterRemovesTheAgentAndStopsItsSchedulesButLeavesTheFiles() {
    AgentLifecycleService service = startup();
    Path dir = AgentFixtures.write(root, "daily-reconcile", DAILY);
    service.register(dir);

    assertTrue(service.unregister("daily-reconcile"));

    assertFalse(registryOf(service).exists("daily-reconcile"));
    assertEquals("unregister:daily-reconcile", sync.calls.getLast());
    assertTrue(Files.exists(dir.resolve("AGENT.md")), "files are not deleted by unregistering");
    assertFalse(service.unregister("daily-reconcile"), "a second call finds nothing");
  }

  @Test
  void aLegacyProfileCannotBeUnregisteredOrReloadedThroughAgentMd() throws IOException {
    Files.writeString(
        profilesDir.resolve("legacy.yaml"),
        "name: legacy\ndescription: old\nprovider: {name: mock, model: m}\n");
    AgentLifecycleService service = startup();

    assertThrows(AgentConflictException.class, () -> service.unregister("legacy"));
    assertThrows(AgentConflictException.class, () -> service.reload("legacy"));
    assertThrows(ProfileNotFoundException.class, () -> service.reload("nobody"));
  }

  @Test
  void aDirectoryThatEscapesTheAgentsRootIsRefused() throws IOException {
    Path outside = temp.resolve("outside");
    AgentFixtures.write(outside, "evil", SIMPLE);
    Files.createDirectories(root);
    Path link = root.resolve("evil");
    boolean linked = makeDirectoryLink(link, outside.resolve("evil"));
    org.junit.jupiter.api.Assumptions.assumeTrue(linked, "cannot create a link or junction here");

    AgentDirectoryScanner.Result result = scanner.scan();

    assertEquals(List.of(), result.agents(), "a linked directory is never an agent");
    assertTrue(result.problems().containsKey("evil"));
  }

  /** A symbolic link, or on Windows a directory junction, which needs no special privilege. */
  private static boolean makeDirectoryLink(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
      return true;
    } catch (UnsupportedOperationException | IOException | SecurityException noSymlink) {
      if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
        return false;
      }
      try {
        Process process =
            new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        return process.waitFor() == 0 && Files.isDirectory(link);
      } catch (IOException | InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }
}
