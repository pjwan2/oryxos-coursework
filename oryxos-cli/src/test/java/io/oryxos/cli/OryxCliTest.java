package io.oryxos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class OryxCliTest {
  /** Records calls; heavy methods are tracked so tests can prove light commands avoid them. */
  private static final class FakeRuntime implements CliRuntime {
    final List<String> chats = new ArrayList<>();
    final List<String> served = new ArrayList<>();
    int heavyCalls;
    int closed;
    RuntimeException chatFailure;

    @Override
    public List<ProfileSummary> listProfiles() {
      return List.of(
          new ProfileSummary("payment-demo", "mock", "mock-echo", List.of("get_payment")));
    }

    @Override
    public List<ToolSummary> listTools() {
      return List.of(new ToolSummary("get_payment", "Lookup a demo payment"));
    }

    @Override
    public StatusReport status() {
      return new StatusReport("0.1.0", ".oryxos/profiles", 1, "./oryxos-dev.db", false);
    }

    @Override
    public String chat(String userId, String profileName, String message) {
      heavyCalls++;
      if (chatFailure != null) {
        throw chatFailure;
      }
      chats.add(userId + "|" + profileName + "|" + message);
      return "reply to: " + message;
    }

    @Override
    public void serve(List<String> springArguments) {
      heavyCalls++;
      served.addAll(springArguments);
    }

    @Override
    public void close() {
      closed++;
    }
  }

  @BeforeAll
  static void plainOutput() {
    // Picocli styles "Usage:" with ANSI escapes when it thinks the terminal supports them.
    System.setProperty("picocli.ansi", "false");
  }

  private final StringWriter out = new StringWriter();
  private final StringWriter err = new StringWriter();

  private int run(FakeRuntime runtime, String stdin, String... args) {
    CommandLine line =
        OryxCli.create(
            runtime,
            new PrintWriter(out, true),
            new PrintWriter(err, true),
            new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
    return line.execute(args);
  }

  @Test
  void oneShotChatPassesUserProfileAndJoinedMessageThenClosesRuntime() {
    var runtime = new FakeRuntime();
    int code = run(runtime, "", "chat", "payment-demo", "--user", "alice", "look", "up", "P1001");
    assertEquals(0, code);
    assertEquals(List.of("alice|payment-demo|look up P1001"), runtime.chats);
    assertTrue(out.toString().contains("reply to: look up P1001"));
    assertEquals(1, runtime.closed);
  }

  @Test
  void interactiveChatSkipsBlankLinesAndStopsAtExit() {
    var runtime = new FakeRuntime();
    int code = run(runtime, "hello\n\n  \nworld\nexit\nnever sent\n", "chat", "payment-demo");
    assertEquals(0, code);
    assertEquals(List.of("local|payment-demo|hello", "local|payment-demo|world"), runtime.chats);
    assertEquals(1, runtime.closed);
  }

  @Test
  void interactiveChatEndsCleanlyAtEndOfInput() {
    var runtime = new FakeRuntime();
    assertEquals(0, run(runtime, "only line", "chat", "payment-demo"));
    assertEquals(1, runtime.chats.size());
  }

  @Test
  void unknownProfileIsAUsageErrorWithOneLineAndNoStackTrace() {
    var runtime = new FakeRuntime();
    runtime.chatFailure = new IllegalArgumentException("Unknown agent profile: nope");
    int code = run(runtime, "", "chat", "nope", "hi");
    assertEquals(2, code);
    assertEquals("Error: Unknown agent profile: nope", err.toString().strip());
    assertEquals(1, runtime.closed, "runtime is released even when the turn fails");
  }

  @Test
  void unexpectedFailureExitsWithOneAndHidesTheStackTrace() {
    var runtime = new FakeRuntime();
    runtime.chatFailure = new IllegalStateException("provider down");
    assertEquals(1, run(runtime, "", "chat", "payment-demo", "hi"));
    assertFalse(err.toString().contains("\tat "));
  }

  @Test
  void lightCommandsNeverTouchTheHeavyRuntime() {
    var runtime = new FakeRuntime();
    assertEquals(0, run(runtime, "", "status"));
    assertEquals(0, run(runtime, "", "profile", "list"));
    assertEquals(0, run(runtime, "", "tool", "list"));
    assertEquals(0, runtime.heavyCalls);
    String text = out.toString();
    assertTrue(text.contains("payment-demo  mock/mock-echo  tools=[get_payment]"));
    assertTrue(text.contains("get_payment  Lookup a demo payment"));
    assertTrue(text.contains("(not created yet)"));
  }

  @Test
  void serveForwardsSpringArguments() {
    var runtime = new FakeRuntime();
    assertEquals(0, run(runtime, "", "serve", "--server.port=8099"), err.toString());
    assertEquals(List.of("--server.port=8099"), runtime.served);
  }

  @Test
  void noArgumentsPrintsUsageAndUnknownCommandFails() {
    var runtime = new FakeRuntime();
    assertEquals(0, run(runtime, ""), err.toString());
    assertTrue(out.toString().contains("Usage: oryxos"), "out=" + out + " err=" + err);
    assertEquals(2, run(runtime, "", "frobnicate"));
  }

  @Test
  void chatWithoutProfileIsRejectedByPicocli() {
    var runtime = new FakeRuntime();
    assertEquals(2, run(runtime, "", "chat"));
    assertEquals(0, runtime.heavyCalls);
  }
}
