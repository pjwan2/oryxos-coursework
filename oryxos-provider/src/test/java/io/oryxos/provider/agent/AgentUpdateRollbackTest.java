package io.oryxos.provider.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.profile.AgentDefinitionException;
import io.oryxos.core.profile.AgentFile;
import io.oryxos.provider.ProfileRegistry;
import io.oryxos.provider.agent.AgentFixtures.RecordingSync;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A failed change must leave no half-registered agent: the files, the registry and the scheduler
 * are exactly as they were, and the previous valid agent keeps working.
 */
class AgentUpdateRollbackTest {
  @TempDir Path temp;

  private Path root;
  private ProfileRegistry registry;
  private RecordingSync sync;
  private AgentLifecycleService service;

  private static String agentMd(String description, String schedules) {
    return "---\ndescription: "
        + description
        + "\nprovider: {name: mock, model: m}\ntools: [read_file]\n"
        + schedules
        + "---\nBody of "
        + description
        + "\n";
  }

  private static final String SCHEDULE =
      "schedules:\n  - {id: nightly, cron: \"0 0 2 * * *\", zone: UTC, message: go}\n";

  @BeforeEach
  void setUp() throws IOException {
    Path profiles = Files.createDirectories(temp.resolve("profiles"));
    root = temp.resolve("agents");
    AgentLoader loader = AgentFixtures.loader(profiles);
    AgentDirectoryScanner scanner = new AgentDirectoryScanner(root, loader);
    AgentStartup.Report startup = AgentStartup.load(AgentFixtures.profiles(profiles), scanner);
    registry = new ProfileRegistry(startup.profiles());
    sync = new RecordingSync();
    service = new AgentLifecycleService(registry, scanner, loader, sync, startup);
    service.create("helper", "original");
    service.update("helper", agentMd("good", SCHEDULE));
    sync.calls.clear();
  }

  private String file() throws IOException {
    return Files.readString(root.resolve("helper/AGENT.md"));
  }

  @Test
  void anInvalidTextIsRefusedBeforeAnythingIsWritten() throws IOException {
    String before = file();

    AgentDefinitionException refused =
        assertThrows(
            AgentDefinitionException.class,
            () -> service.update("helper", "---\ndescription: [oops\n---\nb"));

    assertTrue(refused.getMessage().contains("not valid YAML"), refused.getMessage());
    assertEquals(before, file());
    assertEquals("good", registry.get("helper").description());
    assertEquals(List.of(), sync.calls, "the scheduler was not even asked");
  }

  @Test
  void aToolThatDoesNotExistIsRefusedAndTheOldAgentKeepsItsTools() throws IOException {
    String before = file();

    assertThrows(
        AgentDefinitionException.class,
        () -> service.update("helper", agentMd("bad", "").replace("[read_file]", "[teleport]")));

    assertEquals(before, file());
    assertEquals(List.of("read_file"), registry.get("helper").tools());
  }

  @Test
  void aSchedulerFailureRestoresTheFileTheRegistryAndTheScheduler() throws IOException {
    String before = file();
    sync.failOnRegister = new IllegalStateException("scheduler rejected it");

    assertThrows(
        IllegalStateException.class, () -> service.update("helper", agentMd("new", SCHEDULE)));
    sync.failOnRegister = null;

    assertEquals(before, file(), "the previous AGENT.md is back on disk");
    assertEquals("good", registry.get("helper").description(), "the previous definition is live");
    assertEquals("good", service.get("helper").description());
    assertEquals(
        "register:helper", sync.calls.getLast(), "the scheduler was given the old schedule again");
  }

  @Test
  void aFailedSaveOfSeveralFilesLeavesNoneOfThemBehind() throws IOException {
    String before = file();
    sync.failOnRegister = new IllegalStateException("scheduler rejected it");

    assertThrows(
        IllegalStateException.class,
        () ->
            service.saveFiles(
                "helper",
                List.of(
                    new AgentFile("AGENT.md", agentMd("new", SCHEDULE)),
                    new AgentFile("REFERENCE.md", "ref"),
                    new AgentFile("skills/new.md", "skill"))));
    sync.failOnRegister = null;

    assertEquals(before, file());
    assertFalse(Files.exists(root.resolve("helper/REFERENCE.md")), "a new file was removed again");
    assertFalse(Files.exists(root.resolve("helper/skills/new.md")));
    assertEquals("good", registry.get("helper").description());
  }

  @Test
  void anInvalidAgentMdInASaveStopsBeforeEveryOtherFileToo() throws IOException {
    Files.writeString(root.resolve("helper/REFERENCE.md"), "old reference");

    assertThrows(
        AgentDefinitionException.class,
        () ->
            service.saveFiles(
                "helper",
                List.of(
                    new AgentFile("AGENT.md", "not an agent file"),
                    new AgentFile("REFERENCE.md", "REPLACED"))));

    assertEquals("old reference", Files.readString(root.resolve("helper/REFERENCE.md")));
    assertEquals("good", registry.get("helper").description());
  }

  @Test
  void aFailedCreateLeavesNoDirectoryAndNoRegistration() {
    sync.failOnRegister = new IllegalStateException("scheduler rejected it");

    assertThrows(IllegalStateException.class, () -> service.create("fresh", "new agent"));
    sync.failOnRegister = null;

    assertFalse(registry.exists("fresh"));
    assertFalse(Files.exists(root.resolve("fresh")), "the directory we made is cleaned up");
    assertFalse(service.problems().containsKey("fresh"));
    service.create("fresh", "second try works");
    assertTrue(registry.exists("fresh"));
  }

  @Test
  void aFailedUpdateOfOneAgentDoesNotTouchAnother() throws IOException {
    service.create("other", "bystander");
    String otherBefore = Files.readString(root.resolve("other/AGENT.md"));

    assertThrows(AgentDefinitionException.class, () -> service.update("helper", "garbage"));

    assertEquals(otherBefore, Files.readString(root.resolve("other/AGENT.md")));
    assertEquals("bystander", registry.get("other").description());
  }
}
