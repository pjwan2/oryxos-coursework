package io.oryxos.cli;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Unmatched;

/** Picocli front-end. Every command is a thin shell over {@link CliRuntime}. */
@Command(
    name = "oryxos",
    mixinStandardHelpOptions = true,
    version = "oryxos 0.1.0",
    description = "OryxOS agent runtime",
    subcommands = {
      OryxCli.ChatCommand.class,
      OryxCli.ServeCommand.class,
      OryxCli.StatusCommand.class,
      OryxCli.ProfileCommand.class,
      OryxCli.ToolCommand.class
    })
public final class OryxCli implements Callable<Integer> {
  /** First arguments that mean "run the CLI" rather than "start the server with Spring args". */
  public static final Set<String> COMMANDS =
      Set.of("chat", "serve", "status", "profile", "tool", "-h", "--help", "-V", "--version");

  static final int USAGE_ERROR = 2;

  @Spec CommandSpec spec;

  /** Held in a field set by {@link #create}, because Picocli instantiates subcommands itself. */
  private CliRuntime runtime;

  private InputStream stdin = System.in;

  public static CommandLine create(
      CliRuntime runtime, PrintWriter out, PrintWriter err, InputStream in) {
    OryxCli root = new OryxCli();
    root.runtime = Objects.requireNonNull(runtime, "runtime");
    root.stdin = Objects.requireNonNull(in, "in");
    CommandLine line = new CommandLine(root);
    line.setOut(out);
    line.setErr(err);
    line.setExecutionExceptionHandler(
        (exception, commandLine, parseResult) -> {
          // A one-line message only: stack traces and provider details stay out of the terminal.
          boolean usage = exception instanceof IllegalArgumentException;
          commandLine.getErr().println("Error: " + exception.getMessage());
          return usage ? USAGE_ERROR : 1;
        });
    return line;
  }

  @Override
  public Integer call() {
    showUsage(spec.commandLine());
    return 0;
  }

  static void showUsage(CommandLine line) {
    line.usage(line.getOut());
    line.getOut().flush();
  }

  CliRuntime runtime() {
    return runtime;
  }

  InputStream stdin() {
    return stdin;
  }

  @Command(name = "chat", description = "Talk to an agent (one message, or interactive)")
  static final class ChatCommand implements Callable<Integer> {
    @ParentCommand OryxCli parent;
    @Spec CommandSpec spec;

    @Parameters(index = "0", paramLabel = "<profile>", description = "Agent profile name")
    String profile;

    @Parameters(index = "1..*", arity = "0..*", paramLabel = "<message>")
    List<String> message = List.of();

    @Option(names = "--user", defaultValue = "local", description = "Session owner id")
    String user;

    @Override
    public Integer call() throws Exception {
      PrintWriter out = spec.commandLine().getOut();
      try {
        if (!message.isEmpty()) {
          out.println(parent.runtime().chat(user, profile, String.join(" ", message)));
          return 0;
        }
        return interactive(out);
      } finally {
        parent.runtime().close();
      }
    }

    /** The command owns stdin for the whole interactive session and closes it when done. */
    private int interactive(PrintWriter out) throws java.io.IOException {
      out.println("Chatting with '" + profile + "' as '" + user + "'. Type exit to quit.");
      out.flush();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(parent.stdin(), StandardCharsets.UTF_8))) {
        String line = reader.readLine();
        while (line != null) {
          String text = line.strip();
          if ("exit".equals(text) || "quit".equals(text)) {
            break;
          }
          if (!text.isEmpty()) {
            out.println(parent.runtime().chat(user, profile, text));
            out.flush();
          }
          line = reader.readLine();
        }
      }
      return 0;
    }
  }

  @Command(
      name = "serve",
      description =
          "Start the REST API server; extra options (e.g. --server.port=8081) go to Spring",
      mixinStandardHelpOptions = true)
  static final class ServeCommand implements Callable<Integer> {
    @ParentCommand OryxCli parent;

    /** Options Picocli does not know (e.g. --server.port=8081) are handed to Spring unchanged. */
    @Unmatched List<String> springArguments = new ArrayList<>();

    @Override
    public Integer call() {
      parent.runtime().serve(springArguments);
      return 0;
    }
  }

  @Command(name = "status", description = "Show configuration and storage status")
  static final class StatusCommand implements Callable<Integer> {
    @ParentCommand OryxCli parent;
    @Spec CommandSpec spec;

    @Override
    public Integer call() {
      CliRuntime.StatusReport report = parent.runtime().status();
      PrintWriter out = spec.commandLine().getOut();
      out.println("version:   " + report.version());
      out.println("profiles:  " + report.profileCount() + " in " + report.profileDirectory());
      out.println(
          "database:  "
              + report.databasePath()
              + (report.databaseExists() ? " (exists)" : " (not created yet)"));
      return 0;
    }
  }

  @Command(
      name = "profile",
      description = "Inspect agent profiles",
      subcommands = OryxCli.ProfileCommand.ListCommand.class)
  static final class ProfileCommand implements Callable<Integer> {
    @Spec CommandSpec spec;

    @Override
    public Integer call() {
      showUsage(spec.commandLine());
      return 0;
    }

    @Command(name = "list", description = "List agent profiles")
    static final class ListCommand implements Callable<Integer> {
      @ParentCommand ProfileCommand parent;
      @Spec CommandSpec spec;

      @Override
      public Integer call() {
        OryxCli root = (OryxCli) parent.spec.parent().userObject();
        PrintWriter out = spec.commandLine().getOut();
        List<CliRuntime.ProfileSummary> profiles = root.runtime().listProfiles();
        if (profiles.isEmpty()) {
          out.println("(no profiles found)");
        }
        for (CliRuntime.ProfileSummary p : profiles) {
          out.println(p.name() + "  " + p.provider() + "/" + p.model() + "  tools=" + p.tools());
        }
        return 0;
      }
    }
  }

  @Command(
      name = "tool",
      description = "Inspect tools",
      subcommands = OryxCli.ToolCommand.ListCommand.class)
  static final class ToolCommand implements Callable<Integer> {
    @Spec CommandSpec spec;

    @Override
    public Integer call() {
      showUsage(spec.commandLine());
      return 0;
    }

    @Command(name = "list", description = "List built-in tools")
    static final class ListCommand implements Callable<Integer> {
      @ParentCommand ToolCommand parent;
      @Spec CommandSpec spec;

      @Override
      public Integer call() {
        OryxCli root = (OryxCli) parent.spec.parent().userObject();
        PrintWriter out = spec.commandLine().getOut();
        for (CliRuntime.ToolSummary tool : root.runtime().listTools()) {
          out.println(tool.name() + "  " + tool.description());
        }
        return 0;
      }
    }
  }
}
