package io.oryxos.tool;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.sandbox.Sandbox;
import io.oryxos.core.sandbox.SandboxViolation;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Runs an allowlisted executable with an argument ARRAY: no shell, so no pipes, redirects or
 * expansion. Output and run time are capped. The environment is reduced to what the OS needs.
 */
public final class ShellTool implements OryxTool {
  static final int MAX_OUTPUT_BYTES = 64 * 1024;
  private final Sandbox sandbox;
  private final Duration timeout;

  public ShellTool(Sandbox sandbox, Duration timeout) {
    this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  @Override
  public String getName() {
    return "shell_exec";
  }

  @Override
  public String getDescription() {
    return "Run an allowed program with arguments (no shell features)";
  }

  @Override
  public String getInputSchema() {
    return "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"},"
        + "\"args\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},"
        + "\"required\":[\"command\"]}";
  }

  @Override
  public ToolOutput execute(String argumentsJson, ToolContext context) {
    try {
      JsonNode args = ToolArgs.parse(argumentsJson);
      List<String> argv = new ArrayList<>();
      argv.add(ToolArgs.requireText(args, "command"));
      JsonNode extra = args.get("args");
      if (extra != null) {
        if (!extra.isArray()) {
          return ToolOutput.failed("args must be an array of strings", false);
        }
        for (JsonNode item : extra) {
          if (!item.isTextual()) {
            return ToolOutput.failed("args must be an array of strings", false);
          }
          argv.add(item.asText());
        }
      }
      sandbox.checkCommand(argv);
      return run(argv);
    } catch (SandboxViolation denied) {
      return ToolOutput.denied(denied.getMessage());
    } catch (IllegalArgumentException invalid) {
      return ToolOutput.failed(invalid.getMessage(), false);
    }
  }

  private ToolOutput run(List<String> argv) {
    ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true);
    Map<String, String> env = builder.environment();
    Map<String, String> keep = new HashMap<>();
    for (String key : List.of("PATH", "Path", "SystemRoot")) {
      if (env.containsKey(key)) {
        keep.put(key, env.get(key));
      }
    }
    env.clear();
    env.putAll(keep);
    try {
      Process process = builder.start();
      process.getOutputStream().close();
      ByteArrayOutputStream captured = new ByteArrayOutputStream();
      Thread reader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), captured));
      boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        reader.join(1000);
        return ToolOutput.failed("Command timed out", true);
      }
      reader.join(1000);
      String output;
      synchronized (captured) {
        output = captured.toString(StandardCharsets.UTF_8);
      }
      return process.exitValue() == 0
          ? ToolOutput.ok(output)
          : ToolOutput.failed("Exit code " + process.exitValue() + ": " + output, false);
    } catch (IOException io) {
      return ToolOutput.failed("Unable to start command", false);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return ToolOutput.failed("Command interrupted", true);
    }
  }

  private static void drain(InputStream in, ByteArrayOutputStream sink) {
    byte[] buffer = new byte[4096];
    try (in) {
      int read = in.read(buffer);
      while (read >= 0) {
        synchronized (sink) {
          int room = MAX_OUTPUT_BYTES - sink.size();
          if (room > 0) {
            sink.write(buffer, 0, Math.min(room, read));
          }
        }
        read = in.read(buffer);
      }
    } catch (IOException ignored) {
      // The process ended or was killed; whatever was captured is returned.
    }
  }
}
