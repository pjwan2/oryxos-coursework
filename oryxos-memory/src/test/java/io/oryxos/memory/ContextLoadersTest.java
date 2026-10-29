package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.AgentSession;
import io.oryxos.core.memory.LongTermMemoryStore;
import io.oryxos.core.memory.MemoryEntry;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryStoreException;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.sandbox.Sandbox;
import io.oryxos.core.sandbox.SandboxViolation;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextLoadersTest {
  @TempDir Path temp;
  private static final AgentSession SESSION = new AgentSession("cli:u:p", "p");

  private static AgentProfile profile(List<String> tools, Map<String, Object> bootstrap) {
    return new AgentProfile(
        "p",
        "d",
        Map.of(),
        new AgentProfile.ProviderSettings("mock", "m", 0.1),
        tools,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        bootstrap,
        Map.of());
  }

  private MemoryService serviceWith(String core) {
    var store = new MarkdownMemoryStore(temp.resolve("MEMORY.md"), Clock.systemUTC());
    store.save(MemoryScope.CORE, core);
    return new MemoryService(store, MemoryService.Limits.DEFAULT);
  }

  @Test
  void memoryIsLoadedOnlyForAgentsThatListAMemoryTool() {
    var loader = new MemoryContextLoader(serviceWith("secret preference"));
    assertTrue(
        loader
            .load(profile(List.of("save_memory"), Map.of()), SESSION)
            .contains("secret preference"));
    assertTrue(
        loader
            .load(profile(List.of("recall_memory"), Map.of()), SESSION)
            .contains("secret preference"));
    assertEquals("", loader.load(profile(List.of("get_payment"), Map.of()), SESSION));
    assertEquals("", loader.load(profile(List.of(), Map.of()), SESSION));
  }

  @Test
  void aBrokenMemoryBackendDegradesToNoMemoryInsteadOfFailingTheTurn() {
    LongTermMemoryStore broken =
        new LongTermMemoryStore() {
          public void save(MemoryScope s, String c) {}

          public List<MemoryEntry> readCore() {
            throw new MemoryStoreException("down");
          }

          public List<MemoryEntry> readArchival(int limit) {
            throw new MemoryStoreException("down");
          }

          public List<MemoryEntry> searchArchival(String k, int limit) {
            throw new MemoryStoreException("down");
          }
        };
    var loader = new MemoryContextLoader(new MemoryService(broken, MemoryService.Limits.DEFAULT));
    assertEquals("", loader.load(profile(List.of("save_memory"), Map.of()), SESSION));
  }

  @Test
  void memorySectionNeverContainsConversationHistory() {
    var session = new AgentSession("cli:u:p", "p");
    session.append(
        new io.oryxos.core.agent.AgentMessage(
            io.oryxos.core.agent.AgentMessage.Role.USER, "UNIQUE-CHAT-TEXT"));
    var loader = new MemoryContextLoader(serviceWith("a core fact"));
    assertFalse(
        loader
            .load(profile(List.of("save_memory"), Map.of()), session)
            .contains("UNIQUE-CHAT-TEXT"));
  }

  /** Allows reads only below the temp directory. */
  private Sandbox readOnlyTemp() {
    return new Sandbox() {
      public Path checkPath(Path requested, Access access) {
        Path real = requested.toAbsolutePath().normalize();
        if (access != Access.READ || !real.startsWith(temp)) {
          throw new SandboxViolation("outside");
        }
        return real;
      }

      public void checkCommand(List<String> argv) {
        throw new SandboxViolation("no");
      }

      public void checkUri(URI uri) {
        throw new SandboxViolation("no");
      }
    };
  }

  @Test
  void bootstrapInlineTextAndSandboxedFilesAreLoaded() throws IOException {
    Path guide = temp.resolve("guide.md");
    Files.writeString(guide, "  Always answer briefly.  ");
    var loader = new BootstrapContextLoader(readOnlyTemp());
    String out =
        loader.load(
            profile(
                List.of(),
                Map.of("text", "You work for ACME.", "files", List.of(guide.toString()))),
            SESSION);
    assertEquals("You work for ACME.\nAlways answer briefly.", out);
  }

  @Test
  void bootstrapSkipsFilesOutsideTheSandboxMissingOnesAndDirectories() throws IOException {
    Path outside = Files.createTempFile("outside", ".md");
    try {
      Files.writeString(outside, "SHOULD NOT APPEAR");
      var loader = new BootstrapContextLoader(readOnlyTemp());
      String out =
          loader.load(
              profile(
                  List.of(),
                  Map.of(
                      "files",
                      List.of(
                          outside.toString(),
                          temp.resolve("missing.md").toString(),
                          temp.toString()))),
              SESSION);
      assertEquals("", out);
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void bootstrapLongFilesAreCappedAndAnEmptyBootstrapAddsNothing() throws IOException {
    Path big = temp.resolve("big.md");
    Files.writeString(big, "q".repeat(BootstrapContextLoader.MAX_FILE_CHARS + 500));
    var loader = new BootstrapContextLoader(readOnlyTemp());
    String out = loader.load(profile(List.of(), Map.of("files", List.of(big.toString()))), SESSION);
    assertEquals(BootstrapContextLoader.MAX_FILE_CHARS, out.length());
    assertEquals("", loader.load(profile(List.of(), Map.of()), SESSION));
  }
}
