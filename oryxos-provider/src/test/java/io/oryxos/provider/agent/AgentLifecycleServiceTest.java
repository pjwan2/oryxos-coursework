package io.oryxos.provider.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.ProfileNotFoundException;
import io.oryxos.core.profile.AgentConflictException;
import io.oryxos.core.profile.AgentDefinitionException;
import io.oryxos.core.profile.AgentFile;
import io.oryxos.core.profile.AgentView;
import io.oryxos.core.profile.ArchiveResult;
import io.oryxos.core.workspace.WorkspacePathException;
import io.oryxos.provider.ProfileRegistry;
import io.oryxos.provider.agent.AgentFixtures.RecordingSync;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Create, read, update, archive and save files: one entry point, one set of rules. */
class AgentLifecycleServiceTest {
  @TempDir Path temp;

  private Path profilesDir;
  private Path root;
  private Path archive;
  private ProfileRegistry registry;
  private RecordingSync sync;
  private AgentLifecycleService service;

  private static final String VALID =
      "---\ndescription: edited\nprovider: {name: mock, model: m}\ntools: [read_file]\n---\nNew body\n";

  @BeforeEach
  void setUp() throws IOException {
    profilesDir = Files.createDirectories(temp.resolve("profiles"));
    root = temp.resolve("agents");
    archive = temp.resolve("archive");
    rebuild();
  }

  private void rebuild() {
    AgentLoader loader = AgentFixtures.loader(profilesDir);
    AgentDirectoryScanner scanner = new AgentDirectoryScanner(root, loader);
    AgentStartup.Report startup = AgentStartup.load(AgentFixtures.profiles(profilesDir), scanner);
    registry = new ProfileRegistry(startup.profiles());
    sync = new RecordingSync();
    service =
        new AgentLifecycleService(
            registry,
            scanner,
            loader,
            sync,
            startup,
            new AgentLifecycleService.Options(
                archive,
                "mock",
                "mock-echo",
                Clock.fixed(Instant.parse("2026-03-05T06:07:08Z"), ZoneOffset.UTC)));
  }

  @Test
  void createMakesARunnableAgentFromTheFixedTemplateWithoutAnyModel() throws IOException {
    AgentView created = service.create("helper", "Answers questions about invoices");

    assertEquals("helper", created.name());
    assertEquals("agent-md", created.origin());
    assertTrue(created.editable());
    assertEquals("mock", created.provider());
    assertEquals("mock-echo", created.model());
    assertEquals(List.of(), created.tools());
    assertTrue(created.privateMemory());
    assertTrue(created.instructions().contains("Answers questions about invoices"));
    assertTrue(registry.exists("helper"));
    assertTrue(Files.isRegularFile(root.resolve("helper/AGENT.md")));
    assertEquals(List.of("register:helper"), sync.calls);
  }

  @Test
  void aDescriptionCannotChangeTheStructureOfTheFrontmatter() {
    String nasty = "ok\"\nprovider: {name: evil, model: x}\n---\ninjected: true";

    AgentView created = service.create("quoted", nasty);

    assertEquals("mock", created.provider(), "the injected provider line was only text");
    assertTrue(created.description().contains("provider: {name: evil"));
    assertEquals(List.of(), created.tools());
  }

  @Test
  void createRefusesBadNamesDescriptionsAndAnythingAlreadyThere() throws IOException {
    for (String bad : new String[] {"", "a b", "../x", "x/y", "a".repeat(65), null}) {
      assertThrows(
          AgentDefinitionException.class, () -> service.create(bad, "d"), String.valueOf(bad));
    }
    assertThrows(AgentDefinitionException.class, () -> service.create("fine", " "));
    assertThrows(AgentDefinitionException.class, () -> service.create("fine", "x".repeat(301)));

    service.create("taken", "first");
    assertThrows(AgentConflictException.class, () -> service.create("taken", "second"));

    Files.writeString(
        profilesDir.resolve("old.yaml"),
        "name: old\ndescription: legacy\nprovider: {name: mock, model: m}\n");
    rebuild();
    assertThrows(AgentConflictException.class, () -> service.create("old", "mine"));

    Files.createDirectories(root.resolve("stray"));
    assertThrows(AgentConflictException.class, () -> service.create("stray", "mine"));
    assertTrue(
        Files.isDirectory(root.resolve("stray")), "a directory that was not ours is left alone");
  }

  @Test
  void listAndGetShowLegacyAndDirectoryAgentsWithTheirKind() throws IOException {
    Files.writeString(
        profilesDir.resolve("old.yaml"),
        "name: old\ndescription: legacy\nprovider: {name: mock, model: m}\n");
    rebuild();
    service.create("new-one", "fresh");

    List<AgentView> all = service.list();

    assertEquals(List.of("new-one", "old"), all.stream().map(AgentView::name).toList());
    AgentView legacy = service.get("old");
    assertEquals("legacy-yaml", legacy.origin());
    assertFalse(legacy.editable());
    assertFalse(legacy.privateMemory());
    assertEquals(null, legacy.instructions());
    assertThrows(ProfileNotFoundException.class, () -> service.get("nobody"));
  }

  @Test
  void updateReplacesTheAgentAndTheNewDefinitionIsLive() {
    service.create("helper", "first");

    AgentView updated = service.update("helper", VALID);

    assertEquals("edited", updated.description());
    assertEquals(List.of("read_file"), updated.tools());
    assertEquals("New body", updated.instructions());
    assertEquals("edited", registry.get("helper").description());
    assertEquals(List.of("register:helper", "register:helper"), sync.calls);
  }

  @Test
  void updateOfALegacyOrUnknownAgentIsRefused() throws IOException {
    Files.writeString(
        profilesDir.resolve("old.yaml"),
        "name: old\ndescription: legacy\nprovider: {name: mock, model: m}\n");
    rebuild();

    assertThrows(AgentConflictException.class, () -> service.update("old", VALID));
    assertThrows(AgentConflictException.class, () -> service.archive("old"));
    assertThrows(ProfileNotFoundException.class, () -> service.update("nobody", VALID));
    assertThrows(ProfileNotFoundException.class, () -> service.archive("nobody"));
  }

  @Test
  void archiveMovesTheDirectoryAndItsMemoryAndStopsTheAgent() throws IOException {
    service.create("helper", "first");
    Files.writeString(root.resolve("helper/MEMORY.md"), "## 核心记忆\n- private fact\n");
    Files.writeString(root.resolve("helper/REFERENCE.md"), "ref");

    ArchiveResult result = service.archive("helper");

    assertEquals("helper", result.name());
    assertEquals("archive/helper-20260305T060708Z", result.location());
    assertFalse(Files.exists(root.resolve("helper")), "the live directory is gone");
    Path moved = archive.resolve("helper-20260305T060708Z");
    assertTrue(Files.isRegularFile(moved.resolve("AGENT.md")));
    assertEquals("## 核心记忆\n- private fact\n", Files.readString(moved.resolve("MEMORY.md")));
    assertTrue(Files.isRegularFile(moved.resolve("REFERENCE.md")));
    assertFalse(registry.exists("helper"));
    assertEquals("unregister:helper", sync.calls.getLast());
    assertThrows(ProfileNotFoundException.class, () -> service.get("helper"));
  }

  @Test
  void archivingTwiceInTheSameSecondNeverOverwritesAnEarlierArchive() {
    service.create("helper", "one");
    service.archive("helper");
    service.create("helper", "two");

    ArchiveResult second = service.archive("helper");

    assertEquals("archive/helper-20260305T060708Z-2", second.location());
    assertTrue(Files.isDirectory(archive.resolve("helper-20260305T060708Z")));
    assertTrue(Files.isDirectory(archive.resolve("helper-20260305T060708Z-2")));
  }

  @Test
  void aNameCanBeUsedAgainAfterArchivingAndStartsWithAnEmptyMemory() throws IOException {
    service.create("helper", "one");
    Files.writeString(root.resolve("helper/MEMORY.md"), "- old private fact\n");
    service.archive("helper");

    service.create("helper", "reborn");

    assertFalse(
        Files.exists(root.resolve("helper/MEMORY.md")), "the archived memory did not come back");
  }

  @Test
  void archiveDoesNotDeleteWhenTheArchiveCannotBeWrittenAndTheAgentKeepsRunning()
      throws IOException {
    service.create("helper", "one");
    Files.writeString(archive, "a FILE where the archive directory should be");

    assertThrows(IllegalStateException.class, () -> service.archive("helper"));

    assertTrue(
        Files.isRegularFile(root.resolve("helper/AGENT.md")), "nothing was moved or deleted");
    assertTrue(registry.exists("helper"), "the agent is registered again");
    assertEquals("register:helper", sync.calls.getLast());
  }

  @Test
  void saveFilesWritesTheFilesAtomicallyAndReloadsTheAgent() throws IOException {
    service.create("helper", "first");

    AgentView saved =
        service.saveFiles(
            "helper",
            List.of(
                new AgentFile("AGENT.md", VALID),
                new AgentFile("REFERENCE.md", "reference text"),
                new AgentFile("skills/format.md", "skill text")));

    assertEquals("edited", saved.description());
    assertEquals(List.of("REFERENCE.md", "skills/format.md"), saved.resources());
    assertEquals("reference text", Files.readString(root.resolve("helper/REFERENCE.md")));
    assertEquals("skill text", Files.readString(root.resolve("helper/skills/format.md")));
    try (Stream<Path> leftovers = Files.list(root.resolve("helper"))) {
      assertTrue(
          leftovers.noneMatch(p -> p.getFileName().toString().startsWith(".tmp-")),
          "no temp file is left behind");
    }
  }

  @Test
  void saveFilesRefusesUnsafePathsTypesAndSizesBeforeWritingAnything() throws IOException {
    service.create("helper", "first");
    String before = Files.readString(root.resolve("helper/AGENT.md"));
    for (String bad :
        new String[] {
          "../escape.md",
          "/abs.md",
          "C:/x.md",
          "a\\b.md",
          "x.exe",
          ".hidden.md",
          "a/../../b.md",
          "file.md:stream",
          "MEMORY.md",
          "db.sqlite",
          "api-token.md",
          "application.yaml",
          "CON.md"
        }) {
      assertThrows(
          WorkspacePathException.class,
          () -> service.saveFiles("helper", List.of(new AgentFile(bad, "x"))),
          bad);
    }
    assertThrows(
        WorkspacePathException.class,
        () ->
            service.saveFiles(
                "helper",
                List.of(
                    new AgentFile(
                        "big.md", "x".repeat(AgentLifecycleService.MAX_FILE_BYTES + 1)))));
    assertThrows(AgentDefinitionException.class, () -> service.saveFiles("helper", List.of()));
    assertThrows(
        AgentDefinitionException.class,
        () ->
            service.saveFiles(
                "helper", List.of(new AgentFile("a.md", "1"), new AgentFile("a.md", "2"))));

    assertEquals(before, Files.readString(root.resolve("helper/AGENT.md")));
    assertFalse(Files.exists(temp.resolve("escape.md")));
  }

  @Test
  void problemsListsWhatCouldNotBeAppliedAndAGoodReloadClearsIt() throws IOException {
    AgentFixtures.write(root, "broken", "no frontmatter");
    rebuild();
    assertTrue(service.problems().get("broken").contains("must start with"));

    AgentFixtures.write(
        root, "broken", "---\ndescription: ok\nprovider: {name: mock, model: m}\n---\nbody\n");
    service.register(root.resolve("broken"));

    assertFalse(service.problems().containsKey("broken"));
    assertNotNull(service.get("broken"));
  }

  @Test
  void reconcileBringsTheRegistryInLineWithTheDiskWithoutAnyFileEvent() throws IOException {
    service.create("keep", "stays");
    service.create("gone", "will be deleted by hand");
    AgentFixtures.write(
        root, "added", "---\ndescription: d\nprovider: {name: mock, model: m}\n---\nb\n");
    Files.delete(root.resolve("gone/AGENT.md"));
    Files.delete(root.resolve("gone"));
    AgentFixtures.write(
        root,
        "keep",
        "---\ndescription: changed by hand\nprovider: {name: mock, model: m}\n---\nb\n");

    service.reconcile();

    assertTrue(registry.exists("added"));
    assertFalse(registry.exists("gone"));
    assertEquals("changed by hand", registry.get("keep").description());
  }
}
