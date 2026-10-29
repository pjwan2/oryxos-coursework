package io.oryxos.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.tool.ToolContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShellSecurityTest {
  private final AtomicInteger started = new AtomicInteger();
  private ShellTool tool;
  private ToolContext context;

  /** A process that cannot be started: proves the code reached the launch point. */
  private static Process unstartable(ProcessBuilder builder) throws IOException {
    throw new IOException("not really starting " + builder.command().getFirst());
  }

  @BeforeEach
  void setUp() {
    var ceiling = new SandboxPolicy(List.of(), List.of(), Set.of("git", "date", "java"), Set.of());
    var sandbox = new ProfileSandbox(ceiling);
    AgentProfile profile =
        TestProfiles.with(
            "ops",
            Map.of(
                "commands",
                List.of(
                    "date",
                    Map.of(
                        "name",
                        "git",
                        "args",
                        List.of("status", "log", "-n", "[0-9]{1,3}", "--oneline"),
                        "max_args",
                        4))));
    context = new ToolContext("s1", profile);
    tool =
        new ShellTool(
            sandbox,
            Duration.ofSeconds(5),
            b -> {
              started.incrementAndGet();
              return unstartable(b);
            });
  }

  private ToolOutput run(String command, String... args) {
    try {
      var node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
      node.put("command", command);
      var array = node.putArray("args");
      for (String arg : args) {
        array.add(arg);
      }
      return tool.execute(node.toString(), context);
    } catch (RuntimeException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void declaredProgramWithMatchingArgumentsReachesTheLaunchPoint() {
    ToolOutput out = run("git", "log", "-n", "20", "--oneline");
    assertFalse(out.isDenied(), out.errorMessage());
    assertEquals(1, started.get(), "allowed call must reach the launcher");
  }

  @Test
  void aProgramWithoutAnArgumentRuleMayRunOnlyWithoutArguments() {
    started.set(0);
    assertFalse(tool.execute("{\"command\":\"date\"}", context).isDenied());
    assertEquals(1, started.get());
    assertTrue(run("date", "-d", "tomorrow").isDenied());
    assertEquals(1, started.get(), "the denied call must not launch anything");
  }

  @Test
  void unlistedProgramsAreDeniedAndNeverLaunched() {
    for (String program :
        List.of("rm", "bash", "sh", "cmd", "powershell", "curl", "java", "GIT", "git.exe")) {
      assertTrue(run(program).isDenied(), program);
    }
    assertEquals(0, started.get());
  }

  @Test
  void programsGivenAsPathsOrWholeCommandLinesAreDenied() {
    assertTrue(run("/usr/bin/git", "status").isDenied());
    assertTrue(run("./git", "status").isDenied());
    assertTrue(run("C:\\\\tools\\\\git.exe", "status").isDenied());
    assertTrue(run("git status").isDenied());
    assertTrue(run("git; rm -rf /").isDenied());
    assertTrue(run("git && whoami").isDenied());
    assertEquals(0, started.get());
  }

  @Test
  void argumentsOutsideTheDeclaredPatternsAreDeniedSoOptionInjectionFails() {
    List<List<String>> attacks =
        List.of(
            List.of("status", "--upload-pack=evil"),
            List.of("-c", "core.pager=evil", "log"),
            List.of("log", "-n", "99999"),
            List.of("log", "-n", "5; rm -rf /"),
            List.of("log", "$(whoami)"),
            List.of("log", "`id`"),
            List.of("status", "&&", "whoami"),
            List.of("status", "|", "tee", "x"),
            List.of("status", "> out.txt"),
            List.of("status", "../../etc/passwd"),
            List.of("log", "-n", "5", "--oneline", "extra"), // over max_args
            List.of("status\n--exec=evil"));
    for (List<String> args : attacks) {
      assertTrue(run("git", args.toArray(String[]::new)).isDenied(), args.toString());
    }
    assertEquals(0, started.get(), "no attack may launch a process");
  }

  @Test
  void aDeniedCallReportsAReasonWithoutEchoingTheArguments() {
    ToolOutput out = run("git", "log", "--token=SECRET-ARG");
    assertTrue(out.isDenied());
    assertFalse(out.errorMessage().contains("SECRET-ARG"), out.errorMessage());
  }

  @Test
  void aProfileThatDeclaresNoCommandsCannotRunAnything() {
    var sandbox =
        new ProfileSandbox(new SandboxPolicy(List.of(), List.of(), Set.of("git"), Set.of()));
    var shell =
        new ShellTool(
            sandbox,
            Duration.ofSeconds(5),
            b -> {
              started.incrementAndGet();
              return unstartable(b);
            });
    var none = new ToolContext("s", TestProfiles.with("none", null));
    assertTrue(shell.execute("{\"command\":\"git\"}", none).isDenied());
    assertEquals(0, started.get());
  }

  // ---- limits (fake processes, so the tests are fast and identical on every OS)

  /** A process whose output is whatever the test wants and that may never finish. */
  private static final class FakeProcess extends Process {
    private final InputStream out;
    private final boolean finishes;
    final AtomicInteger destroyed = new AtomicInteger();

    FakeProcess(InputStream out, boolean finishes) {
      this.out = out;
      this.finishes = finishes;
    }

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return out;
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
      if (!finishes) {
        Thread.sleep(Math.min(unit.toMillis(timeout), 200));
      }
      return finishes;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void destroy() {
      destroyed.incrementAndGet();
    }

    @Override
    public Process destroyForcibly() {
      destroyed.incrementAndGet();
      return this;
    }
  }

  @Test
  void aCommandThatRunsTooLongIsKilledAndReportedAsAFailure() {
    FakeProcess hung = new FakeProcess(InputStream.nullInputStream(), false);
    var sandbox =
        new ProfileSandbox(new SandboxPolicy(List.of(), List.of(), Set.of("date"), Set.of()));
    var shell = new ShellTool(sandbox, Duration.ofMillis(100), b -> hung);
    var ctx = new ToolContext("s", TestProfiles.with("p", Map.of("commands", List.of("date"))));
    ToolOutput out = shell.execute("{\"command\":\"date\"}", ctx);
    assertFalse(out.success());
    assertEquals("Command timed out", out.errorMessage());
    assertTrue(out.retryable());
    assertTrue(hung.destroyed.get() >= 1, "the runaway process must be destroyed");
  }

  @Test
  void outputBeyondTheCapIsCutOff() {
    byte[] huge = new byte[ShellTool.MAX_OUTPUT_BYTES * 4];
    java.util.Arrays.fill(huge, (byte) 'x');
    FakeProcess chatty = new FakeProcess(new ByteArrayInputStream(huge), true);
    var sandbox =
        new ProfileSandbox(new SandboxPolicy(List.of(), List.of(), Set.of("date"), Set.of()));
    var shell = new ShellTool(sandbox, Duration.ofSeconds(5), b -> chatty);
    var ctx = new ToolContext("s", TestProfiles.with("p", Map.of("commands", List.of("date"))));
    ToolOutput out = shell.execute("{\"command\":\"date\"}", ctx);
    assertTrue(out.success());
    assertEquals(ShellTool.MAX_OUTPUT_BYTES, out.content().length());
  }

  @Test
  void theChildEnvironmentIsReducedToWhatTheOsNeeds() {
    var captured = new java.util.concurrent.atomic.AtomicReference<Map<String, String>>();
    var sandbox =
        new ProfileSandbox(new SandboxPolicy(List.of(), List.of(), Set.of("date"), Set.of()));
    var shell =
        new ShellTool(
            sandbox,
            Duration.ofSeconds(5),
            b -> {
              captured.set(Map.copyOf(b.environment()));
              return new FakeProcess(InputStream.nullInputStream(), true);
            });
    var ctx = new ToolContext("s", TestProfiles.with("p", Map.of("commands", List.of("date"))));
    shell.execute("{\"command\":\"date\"}", ctx);
    for (String key : captured.get().keySet()) {
      assertTrue(Set.of("PATH", "Path", "SystemRoot").contains(key), "unexpected variable " + key);
    }
  }
}
