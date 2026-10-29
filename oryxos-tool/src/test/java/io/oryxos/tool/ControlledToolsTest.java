package io.oryxos.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.tool.ToolContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlledToolsTest {
  private static final ToolContext CTX = new ToolContext("s1", AgentProfile.example("mock", "m"));
  @TempDir Path temp;
  private HttpServer server;
  private int port;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ok",
        ex -> {
          byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, body.length);
          ex.getResponseBody().write(body);
          ex.close();
        });
    server.createContext(
        "/boom",
        ex -> {
          ex.sendResponseHeaders(503, -1);
          ex.close();
        });
    server.createContext(
        "/redirect",
        ex -> {
          ex.getResponseHeaders().add("Location", "http://evil.invalid/");
          ex.sendResponseHeaders(302, -1);
          ex.close();
        });
    server.start();
    port = server.getAddress().getPort();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private AllowListSandbox sandbox(Set<String> commands) {
    return new AllowListSandbox(
        new SandboxPolicy(List.of(temp), List.of(temp), commands, Set.of("127.0.0.1")));
  }

  @Test
  void fileWriteThenReadRoundTripsInsideTheRoot() {
    var sb = sandbox(Set.of());
    String file = temp.resolve("note.txt").toString().replace("\\", "\\\\");
    ToolOutput write =
        new FileWriteTool(sb).execute("{\"path\":\"" + file + "\",\"content\":\"héllo\"}", CTX);
    assertTrue(write.success());
    ToolOutput read = new FileReadTool(sb).execute("{\"path\":\"" + file + "\"}", CTX);
    assertEquals("héllo", read.content());
  }

  @Test
  void fileToolsDenyPathsOutsideTheRootWithADeniedResult() throws IOException {
    Path outside = Files.createTempFile("outside", ".txt");
    try {
      String file = outside.toString().replace("\\", "\\\\");
      var sb = sandbox(Set.of());
      ToolOutput read = new FileReadTool(sb).execute("{\"path\":\"" + file + "\"}", CTX);
      ToolOutput write =
          new FileWriteTool(sb).execute("{\"path\":\"" + file + "\",\"content\":\"x\"}", CTX);
      assertTrue(read.isDenied());
      assertTrue(write.isDenied());
      assertEquals("", Files.readString(outside));
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void fileReadRejectsBadArgumentsAndDirectories() {
    var tool = new FileReadTool(sandbox(Set.of()));
    assertFalse(tool.execute("not json", CTX).success());
    assertFalse(tool.execute("{}", CTX).success());
    String dir = temp.toString().replace("\\", "\\\\");
    assertEquals(
        "Not a readable file", tool.execute("{\"path\":\"" + dir + "\"}", CTX).errorMessage());
  }

  @Test
  void fileReadTruncatesLargeFiles() throws IOException {
    Path big = temp.resolve("big.txt");
    Files.writeString(big, "a".repeat(FileReadTool.MAX_BYTES + 10));
    String file = big.toString().replace("\\", "\\\\");
    ToolOutput out =
        new FileReadTool(sandbox(Set.of())).execute("{\"path\":\"" + file + "\"}", CTX);
    assertTrue(out.content().endsWith("[truncated]"));
  }

  @Test
  void httpGetReturnsBodyForAllowedHost() {
    var tool = new HttpGetTool(sandbox(Set.of()), HttpGetTool.defaultClient());
    ToolOutput out = tool.execute("{\"url\":\"http://127.0.0.1:" + port + "/ok\"}", CTX);
    assertTrue(out.success());
    assertEquals("hello", out.content());
  }

  @Test
  void httpGetReportsServerErrorsAsRetryableFailuresAndNeverFollowsRedirects() {
    var tool = new HttpGetTool(sandbox(Set.of()), HttpGetTool.defaultClient());
    ToolOutput boom = tool.execute("{\"url\":\"http://127.0.0.1:" + port + "/boom\"}", CTX);
    assertFalse(boom.success());
    assertTrue(boom.retryable());
    ToolOutput redirect = tool.execute("{\"url\":\"http://127.0.0.1:" + port + "/redirect\"}", CTX);
    assertFalse(redirect.success());
    assertTrue(redirect.errorMessage().contains("302"));
  }

  @Test
  void httpGetDeniesUnlistedHostBeforeAnyRequest() {
    var tool = new HttpGetTool(sandbox(Set.of()), HttpGetTool.defaultClient());
    assertTrue(tool.execute("{\"url\":\"http://localhost:" + port + "/ok\"}", CTX).isDenied());
  }

  @Test
  void shellRunsAllowlistedProgramWithoutAShell() {
    // "java -version" exists wherever these tests run; output goes to stderr (merged).
    var tool = new ShellTool(sandbox(Set.of("java")), Duration.ofSeconds(20));
    ToolOutput out = tool.execute("{\"command\":\"java\",\"args\":[\"-version\"]}", CTX);
    assertTrue(out.success(), out.errorMessage());
    assertTrue(out.content().toLowerCase().contains("version"));
  }

  @Test
  void shellDeniesUnlistedCommandsAndShellMetacharactersAreNotInterpreted() {
    var tool = new ShellTool(sandbox(Set.of("java")), Duration.ofSeconds(20));
    assertTrue(tool.execute("{\"command\":\"rm\",\"args\":[\"-rf\",\"x\"]}", CTX).isDenied());
    assertTrue(tool.execute("{\"command\":\"java && rm x\"}", CTX).isDenied());
    ToolOutput badArgs = tool.execute("{\"command\":\"java\",\"args\":\"-version\"}", CTX);
    assertFalse(badArgs.success());
    assertFalse(badArgs.isDenied());
  }

  @Test
  void shellFailureExitCodeIsAFailureNotASuccess() {
    var tool = new ShellTool(sandbox(Set.of("java")), Duration.ofSeconds(20));
    ToolOutput out = tool.execute("{\"command\":\"java\",\"args\":[\"--no-such-flag\"]}", CTX);
    assertFalse(out.success());
    assertTrue(out.errorMessage().startsWith("Exit code"));
  }
}
