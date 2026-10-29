package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.ProfileContext;
import io.oryxos.storage.ToolInvocationRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real Spring context, real tools, real SQLite audit, mock model. Four agents with different
 * sandbox declarations share one operator ceiling (ws/a and ws/b readable, ws/a writable).
 */
@SpringBootTest
class SandboxIntegrationTest {
  private static final Path DIR = tempDir();
  private static final Path WS = DIR.resolve("ws");

  private static Path tempDir() {
    try {
      Path dir = Files.createTempDirectory("oryxos-sandbox-it");
      Files.createDirectories(dir.resolve("ws/a"));
      Files.createDirectories(dir.resolve("ws/b"));
      Files.createDirectories(dir.resolve("ws/outside"));
      Files.writeString(dir.resolve("ws/a/data.txt"), "A-DATA");
      Files.writeString(dir.resolve("ws/b/data.txt"), "B-DATA");
      Files.writeString(dir.resolve("ws/outside/data.txt"), "OUTSIDE-DATA");
      Path profiles = Files.createDirectories(dir.resolve("profiles"));
      Files.writeString(
          profiles.resolve("agent-a.yaml"),
          profile(
              "agent-a",
              "[file_read, file_write]",
              "    read_roots: [\""
                  + slash(dir.resolve("ws/a"))
                  + "\"]\n    write_roots: [\""
                  + slash(dir.resolve("ws/a"))
                  + "\"]\n"));
      Files.writeString(
          profiles.resolve("agent-b.yaml"),
          profile(
              "agent-b",
              "[file_read]",
              "    read_roots: [\"" + slash(dir.resolve("ws/b")) + "\"]\n"));
      Files.writeString(
          profiles.resolve("agent-c.yaml"), profile("agent-c", "[file_read, file_write]", null));
      Files.writeString(
          profiles.resolve("agent-greedy.yaml"),
          profile(
              "agent-greedy",
              "[file_read]",
              "    read_roots: [\""
                  + slash(dir.resolve("ws/outside"))
                  + "\", \""
                  + slash(dir.resolve("ws/b"))
                  + "\"]\n"));
      return dir;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String slash(Path path) {
    return path.toString().replace('\\', '/');
  }

  private static String profile(String name, String tools, String sandboxBody) {
    return "name: "
        + name
        + "\ndescription: sandbox it\nprovider: {name: mock, model: m, temperature: 0.2}\n"
        + "tools: "
        + tools
        + "\nsettings:\n  max_iterations: 5\n"
        + (sandboxBody == null ? "" : "  sandbox:\n" + sandboxBody);
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry registry) {
    registry.add("oryxos.profile-directory", () -> slash(DIR.resolve("profiles")));
    registry.add(
        "oryxos.sandbox.read-roots", () -> slash(WS.resolve("a")) + "," + slash(WS.resolve("b")));
    registry.add("oryxos.sandbox.write-roots", () -> slash(WS.resolve("a")));
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + slash(DIR.resolve("sandbox.db")));
    registry.add("oryxos.memory.markdown-path", () -> slash(DIR.resolve("MEMORY.md")));
  }

  @Autowired
  @Qualifier("springAiAgentService")
  AgentService agent;

  @Autowired ToolInvocationRepository audit;

  private static String user() {
    return "u" + UUID.randomUUID().toString().replace("-", "");
  }

  private String read(String profile, String user, Path file) {
    return agent.process("web", user, profile, "tool:file_read {\"path\":\"" + slash(file) + "\"}");
  }

  @Test
  void twoAgentsWithDifferentDeclarationsCannotReadEachOthersFiles() {
    assertEquals("Tool result: A-DATA", read("agent-a", user(), WS.resolve("a/data.txt")));
    assertEquals("Tool result: B-DATA", read("agent-b", user(), WS.resolve("b/data.txt")));
    assertTrue(read("agent-a", user(), WS.resolve("b/data.txt")).contains("SANDBOX_DENIED"));
    assertTrue(read("agent-b", user(), WS.resolve("a/data.txt")).contains("SANDBOX_DENIED"));
  }

  @Test
  void anAgentThatDeclaresNoSandboxCannotTouchAnythingEvenInsideTheOperatorCeiling() {
    assertTrue(read("agent-c", user(), WS.resolve("a/data.txt")).contains("SANDBOX_DENIED"));
    assertTrue(read("agent-c", user(), WS.resolve("b/data.txt")).contains("SANDBOX_DENIED"));
  }

  @Test
  void anAgentCannotWidenItsOwnPermissionsBeyondTheOperatorCeiling() {
    assertEquals("Tool result: B-DATA", read("agent-greedy", user(), WS.resolve("b/data.txt")));
    String outside = read("agent-greedy", user(), WS.resolve("outside/data.txt"));
    assertTrue(outside.contains("SANDBOX_DENIED"), outside);
    assertFalse(outside.contains("OUTSIDE-DATA"));
  }

  @Test
  void pathTraversalAndAbsoluteEscapesAreDeniedThroughTheWholeStack() {
    String u = user();
    assertTrue(read("agent-a", u, WS.resolve("a/../b/data.txt")).contains("SANDBOX_DENIED"));
    assertTrue(
        read("agent-a", u, WS.resolve("a/../../ws/outside/data.txt")).contains("SANDBOX_DENIED"));
    assertTrue(read("agent-a", u, DIR.resolve("profiles/agent-a.yaml")).contains("SANDBOX_DENIED"));
  }

  @Test
  void writesAreDeniedOutsideTheWriteRootAndNothingIsCreated() {
    Path forbidden = WS.resolve("b/created-by-attack.txt");
    String reply =
        agent.process(
            "web",
            user(),
            "agent-a",
            "tool:file_write {\"path\":\"" + slash(forbidden) + "\",\"content\":\"pwn\"}");
    assertTrue(reply.contains("SANDBOX_DENIED"), reply);
    assertFalse(Files.exists(forbidden), "a denied write must leave no file behind");

    Path allowed = WS.resolve("a/written-by-agent.txt");
    String ok =
        agent.process(
            "web",
            user(),
            "agent-a",
            "tool:file_write {\"path\":\"" + slash(allowed) + "\",\"content\":\"hello\"}");
    assertTrue(ok.contains("Wrote 5 characters"), ok);
    assertTrue(Files.exists(allowed));
  }

  @Test
  void anAgentWithoutTheToolInItsProfileIsNeverOfferedIt() {
    String reply =
        agent.process(
            "web", user(), "agent-b", "tool:file_write {\"path\":\"x\",\"content\":\"y\"}");
    assertEquals("Tool not available: file_write", reply);
  }

  @Test
  void everyDeniedCallIsAuditedAsFailedSandboxDeniedWithItsSessionAndTool() {
    String u = user();
    read("agent-c", u, WS.resolve("a/data.txt"));
    read("agent-a", u, WS.resolve("b/data.txt"));
    read("agent-a", u, WS.resolve("a/data.txt")); // allowed, for contrast

    var denied = audit.findBySessionId("web:" + u + ":agent-c");
    assertEquals(1, denied.size());
    assertEquals("file_read", denied.getFirst().getToolName());
    assertFalse(denied.getFirst().isSuccess());
    assertEquals("SandboxDenied", denied.getFirst().getErrorCategory());

    var mixed = audit.findBySessionId("web:" + u + ":agent-a");
    assertEquals(2, mixed.size());
    assertEquals(
        1, mixed.stream().filter(r -> "SandboxDenied".equals(r.getErrorCategory())).count());
    assertEquals(1, mixed.stream().filter(r -> r.isSuccess()).count());
  }

  @Test
  void theCallersProfileDoesNotLeakIntoTheNextRequestOnTheSameThread() {
    String u = user();
    assertEquals("Tool result: A-DATA", read("agent-a", u, WS.resolve("a/data.txt")));
    assertNull(ProfileContext.current(), "cleared after a successful request");
    assertTrue(read("agent-c", u, WS.resolve("a/data.txt")).contains("SANDBOX_DENIED"));
    assertNull(ProfileContext.current(), "cleared after a denied request");
    assertTrue(read("agent-b", u, WS.resolve("a/data.txt")).contains("SANDBOX_DENIED"));
    assertEquals("Tool result: B-DATA", read("agent-b", u, WS.resolve("b/data.txt")));
  }

  @Test
  void aFailedRequestLeavesNoPermissionsBehindForTheNextOne() {
    assertThrows(
        IllegalArgumentException.class, () -> agent.process("web", user(), "no-such-agent", "hi"));
    assertNull(ProfileContext.current());
    assertThrows(
        IllegalArgumentException.class, () -> agent.process("web", user(), "agent-a", "  "));
    assertNull(ProfileContext.current());
    assertTrue(read("agent-c", user(), WS.resolve("a/data.txt")).contains("SANDBOX_DENIED"));
  }

  @Test
  void concurrentRequestsFromDifferentAgentsNeverSeeEachOthersPermissions() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 60; i++) {
        final int n = i;
        results.add(
            pool.submit(
                () -> {
                  String u = user();
                  return switch (n % 3) {
                    case 0 ->
                        read("agent-a", u, WS.resolve("a/data.txt")).equals("Tool result: A-DATA")
                            && read("agent-a", u, WS.resolve("b/data.txt"))
                                .contains("SANDBOX_DENIED");
                    case 1 ->
                        read("agent-b", u, WS.resolve("b/data.txt")).equals("Tool result: B-DATA")
                            && read("agent-b", u, WS.resolve("a/data.txt"))
                                .contains("SANDBOX_DENIED");
                    default ->
                        read("agent-c", u, WS.resolve("a/data.txt")).contains("SANDBOX_DENIED")
                            && read("agent-c", u, WS.resolve("b/data.txt"))
                                .contains("SANDBOX_DENIED");
                  };
                }));
      }
      for (Future<Boolean> result : results) {
        assertTrue(result.get(), "a request saw permissions that belong to another agent");
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
