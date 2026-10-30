package io.oryxos.provider.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.oryxos.core.profile.AgentDefinitionException;
import io.oryxos.core.workspace.WorkspaceEntry;
import io.oryxos.core.workspace.WorkspaceFile;
import io.oryxos.core.workspace.WorkspacePathException;
import io.oryxos.provider.ProfileRegistry;
import io.oryxos.provider.agent.AgentFixtures.RecordingSync;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The workspace API reaches the files of registered agents and nothing else. */
class AgentWorkspaceTest {
  @TempDir Path temp;

  private Path root;
  private AgentLifecycleService lifecycle;
  private AgentWorkspace workspace;
  private ProfileRegistry registry;

  @BeforeEach
  void setUp() throws IOException {
    Path profiles = Files.createDirectories(temp.resolve("profiles"));
    root = temp.resolve("agents");
    AgentLoader loader = AgentFixtures.loader(profiles);
    AgentDirectoryScanner scanner = new AgentDirectoryScanner(root, loader);
    AgentStartup.Report startup = AgentStartup.load(AgentFixtures.profiles(profiles), scanner);
    registry = new ProfileRegistry(startup.profiles());
    lifecycle = new AgentLifecycleService(registry, scanner, loader, new RecordingSync(), startup);
    workspace = new AgentWorkspace(lifecycle);
    lifecycle.create("helper", "first");
    Files.writeString(root.resolve("helper/REFERENCE.md"), "reference");
    Files.createDirectories(root.resolve("helper/skills"));
    Files.writeString(root.resolve("helper/skills/format.md"), "skill");
    Files.writeString(root.resolve("helper/MEMORY.md"), "- private memory line\n");
    lifecycle.create("other", "second");
    Files.writeString(temp.resolve("outside-secret.md"), "OUTSIDE");
  }

  private void refused(String path) {
    assertThrows(WorkspacePathException.class, () -> workspace.read(path), "read " + path);
    assertThrows(WorkspacePathException.class, () -> workspace.save(path, "x"), "write " + path);
  }

  @Test
  void theTreeListsOnlyRegisteredAgentsAndHidesTemporaryAndHiddenFiles() throws IOException {
    Files.writeString(root.resolve("helper/.hidden.md"), "h");
    Files.createDirectories(root.resolve("unregistered"));
    Files.writeString(root.resolve("unregistered/AGENT.md"), "not registered");

    List<String> paths = workspace.tree().stream().map(WorkspaceEntry::path).toList();

    assertTrue(paths.contains("helper"));
    assertTrue(paths.contains("helper/AGENT.md"));
    assertTrue(paths.contains("helper/skills/format.md"));
    assertTrue(paths.contains("other/AGENT.md"));
    assertFalse(paths.stream().anyMatch(p -> p.contains("hidden")));
    assertFalse(paths.stream().anyMatch(p -> p.startsWith("unregistered")));
  }

  @Test
  void aTextFileOfARegisteredAgentCanBeRead() {
    WorkspaceFile file = workspace.read("helper/REFERENCE.md");
    assertEquals("reference", file.content());
    assertTrue(file.editable());
    assertEquals("helper/REFERENCE.md", file.path());
  }

  @Test
  void theMemoryFileIsReadableButNeverWritableHere() {
    WorkspaceFile memory = workspace.read("helper/MEMORY.md");
    assertTrue(memory.content().contains("private memory line"));
    assertFalse(memory.editable());
    assertThrows(WorkspacePathException.class, () -> workspace.save("helper/MEMORY.md", "wiped"));
  }

  @Test
  void parentTraversalAbsolutePathsDrivesStreamsAndBackslashesAreRefused() {
    for (String bad :
        new String[] {
          "../outside-secret.md",
          "helper/../../outside-secret.md",
          "helper/../other/AGENT.md",
          "/etc/passwd",
          "/helper/AGENT.md",
          "C:/Windows/win.ini",
          "C:\\Windows\\win.ini",
          "helper\\AGENT.md",
          "helper/AGENT.md:evil",
          "helper//AGENT.md",
          "helper/./AGENT.md",
          "helper/%2e%2e/x.md",
          "helper/AGENT.md\u0000.png",
          ""
        }) {
      refused(bad);
    }
    assertEquals("OUTSIDE", readOutside(), "the file next to the workspace was never touched");
  }

  private String readOutside() {
    try {
      return Files.readString(temp.resolve("outside-secret.md"));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void hiddenDeviceNamedAndSecretLookingFilesAndOtherFileTypesAreRefused() throws IOException {
    Files.writeString(root.resolve("helper/api-token.md"), "t");
    Files.writeString(root.resolve("helper/tool.exe"), "binary");
    Files.writeString(root.resolve("helper/data.sqlite"), "db");
    Files.writeString(root.resolve("helper/application.yaml"), "spring: {}");
    for (String bad :
        new String[] {
          "helper/.env",
          "helper/.hidden.md",
          "helper/api-token.md",
          "helper/tool.exe",
          "helper/data.sqlite",
          "helper/application.yaml",
          "helper/CON.md",
          "helper/nul",
          "helper/x.md "
        }) {
      refused(bad);
    }
  }

  @Test
  void onlyDirectoriesOfRegisteredAgentsAreReachable() throws IOException {
    Files.createDirectories(root.resolve("stranger"));
    Files.writeString(root.resolve("stranger/AGENT.md"), "x");
    refused("stranger/AGENT.md");
    refused("nobody/AGENT.md");
    assertThrows(WorkspacePathException.class, () -> workspace.save("newdir/file.md", "x"));
    assertFalse(Files.exists(root.resolve("newdir")), "writing does not create agent directories");
  }

  @Test
  void aFileDirectlyInsideTheRootIsNotAnAgentFile() throws IOException {
    Files.writeString(root.resolve("notes.md"), "root file");
    refused("notes.md");
  }

  @Test
  void aLinkOrJunctionOutOfTheWorkspaceIsNotFollowed() throws IOException {
    Path outsideDir = Files.createDirectories(temp.resolve("outside-dir"));
    Files.writeString(outsideDir.resolve("secret.md"), "OUTSIDE-DIR-SECRET");
    Path link = root.resolve("helper/escape");
    assumeTrue(
        makeDirectoryLink(link, outsideDir), "cannot create a symbolic link or junction here");

    refused("helper/escape/secret.md");
    assertFalse(
        workspace.tree().stream().anyMatch(e -> e.path().startsWith("helper/escape/")),
        "the tree does not walk into it");
    assertEquals("OUTSIDE-DIR-SECRET", Files.readString(outsideDir.resolve("secret.md")));
  }

  @Test
  void aFileThatIsALinkIsNotFollowed() throws IOException {
    Path link = root.resolve("helper/linked.md");
    boolean made;
    try {
      Files.createSymbolicLink(link, temp.resolve("outside-secret.md"));
      made = true;
    } catch (UnsupportedOperationException | IOException | SecurityException e) {
      made = false;
    }
    assumeTrue(made, "this account cannot create symbolic links");
    refused("helper/linked.md");
  }

  @Test
  void savingAnotherFileWritesItAndRefreshesTheFileListOfTheAgent() throws IOException {
    WorkspaceFile saved = workspace.save("helper/skills/new.md", "# new skill");

    assertEquals("# new skill", saved.content());
    assertEquals("# new skill", Files.readString(root.resolve("helper/skills/new.md")));
    assertTrue(lifecycle.definition("helper").orElseThrow().resources().contains("skills/new.md"));
  }

  @Test
  void savingAgentMdValidatesAndReRegistersAndAnInvalidOneChangesNothing() throws IOException {
    String valid =
        "---\ndescription: via workspace\nprovider: {name: mock, model: m}\n---\nBody V2\n";

    workspace.save("helper/AGENT.md", valid);
    assertEquals("via workspace", registry.get("helper").description());

    String before = Files.readString(root.resolve("helper/AGENT.md"));
    AgentDefinitionException invalid =
        assertThrows(
            AgentDefinitionException.class,
            () -> workspace.save("helper/AGENT.md", "---\ndescription: d\n---\nno provider"));
    assertTrue(invalid.getMessage().contains("provider is required"), invalid.getMessage());
    assertEquals(before, Files.readString(root.resolve("helper/AGENT.md")));
    assertEquals("via workspace", registry.get("helper").description());
  }

  @Test
  void aFileLargerThanTheLimitIsRefusedOnReadAndOnWrite() throws IOException {
    Files.writeString(root.resolve("helper/big.md"), "x".repeat(AgentWorkspace.MAX_READ_BYTES + 1));

    assertThrows(WorkspacePathException.class, () -> workspace.read("helper/big.md"));
    assertThrows(
        WorkspacePathException.class,
        () ->
            workspace.save("helper/huge.md", "x".repeat(AgentLifecycleService.MAX_FILE_BYTES + 1)));
    assertFalse(Files.exists(root.resolve("helper/huge.md")));
  }

  @Test
  void aFileThatIsNotUtf8TextIsRefused() throws IOException {
    Files.write(root.resolve("helper/latin.md"), new byte[] {(byte) 0xE9, (byte) 0xFF, 'a'});
    assertThrows(WorkspacePathException.class, () -> workspace.read("helper/latin.md"));
  }

  /** A symbolic link, or on Windows a directory junction, which needs no special privilege. */
  static boolean makeDirectoryLink(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
      return true;
    } catch (UnsupportedOperationException | IOException | SecurityException noSymlink) {
      if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
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
