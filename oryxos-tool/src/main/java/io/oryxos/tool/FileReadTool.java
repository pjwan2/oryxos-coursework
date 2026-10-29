package io.oryxos.tool;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.sandbox.Sandbox;
import io.oryxos.core.sandbox.SandboxViolation;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/** Reads a UTF-8 text file inside the sandbox read roots, truncated to a hard byte limit. */
public final class FileReadTool implements OryxTool {
  static final int MAX_BYTES = 64 * 1024;
  private final Sandbox sandbox;

  public FileReadTool(Sandbox sandbox) {
    this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
  }

  @Override
  public String getName() {
    return "file_read";
  }

  @Override
  public String getDescription() {
    return "Read a text file from an allowed directory";
  }

  @Override
  public String getInputSchema() {
    return "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},"
        + "\"required\":[\"path\"]}";
  }

  @Override
  public ToolOutput execute(String argumentsJson, ToolContext context) {
    try {
      JsonNode args = ToolArgs.parse(argumentsJson);
      Path requested = Path.of(ToolArgs.requireText(args, "path"));
      Path path = sandbox.forProfile(context.profile()).checkPath(requested, Sandbox.Access.READ);
      if (!Files.isRegularFile(path)) {
        return ToolOutput.failed("Not a readable file", false);
      }
      try (InputStream in = Files.newInputStream(path)) {
        byte[] data = in.readNBytes(MAX_BYTES + 1);
        boolean truncated = data.length > MAX_BYTES;
        int length = Math.min(data.length, MAX_BYTES);
        String text = new String(data, 0, length, StandardCharsets.UTF_8);
        return ToolOutput.ok(truncated ? text + "\n[truncated]" : text);
      }
    } catch (SandboxViolation denied) {
      return ToolOutput.denied(denied.getMessage());
    } catch (InvalidPathException unparsable) {
      // The platform itself cannot parse it (e.g. ':' or trailing space on Windows); do not echo
      // it.
      return ToolOutput.denied("Invalid path");
    } catch (IllegalArgumentException invalid) {
      return ToolOutput.failed(invalid.getMessage(), false);
    } catch (IOException io) {
      return ToolOutput.failed("Unable to read file", false);
    }
  }
}
