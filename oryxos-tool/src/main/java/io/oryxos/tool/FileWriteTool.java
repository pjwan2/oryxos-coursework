package io.oryxos.tool;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.sandbox.Sandbox;
import io.oryxos.core.sandbox.SandboxViolation;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/** Writes a UTF-8 text file, only inside the sandbox write roots. The parent must already exist. */
public final class FileWriteTool implements OryxTool {
  static final int MAX_CHARS = 256 * 1024;
  private final Sandbox sandbox;

  public FileWriteTool(Sandbox sandbox) {
    this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
  }

  @Override
  public String getName() {
    return "file_write";
  }

  @Override
  public String getDescription() {
    return "Write (overwrite) a text file in an allowed directory";
  }

  @Override
  public String getInputSchema() {
    return "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},"
        + "\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}";
  }

  @Override
  public ToolOutput execute(String argumentsJson, ToolContext context) {
    try {
      JsonNode args = ToolArgs.parse(argumentsJson);
      String content = ToolArgs.requireText(args, "content");
      if (content.length() > MAX_CHARS) {
        return ToolOutput.failed("content too large", false);
      }
      Path requested = Path.of(ToolArgs.requireText(args, "path"));
      Path path = sandbox.forProfile(context.profile()).checkPath(requested, Sandbox.Access.WRITE);
      Files.writeString(path, content, StandardCharsets.UTF_8);
      return ToolOutput.ok("Wrote " + content.length() + " characters");
    } catch (SandboxViolation denied) {
      return ToolOutput.denied(denied.getMessage());
    } catch (InvalidPathException unparsable) {
      // The platform itself cannot parse it (e.g. ':' or trailing space on Windows); do not echo
      // it.
      return ToolOutput.denied("Invalid path");
    } catch (IllegalArgumentException invalid) {
      return ToolOutput.failed(invalid.getMessage(), false);
    } catch (IOException io) {
      return ToolOutput.failed("Unable to write file", false);
    }
  }
}
