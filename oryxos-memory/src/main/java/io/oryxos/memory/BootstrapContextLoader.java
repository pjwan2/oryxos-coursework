package io.oryxos.memory;

import io.oryxos.core.agent.AgentSession;
import io.oryxos.core.agent.ContextLoader;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.sandbox.Sandbox;
import io.oryxos.core.sandbox.SandboxViolation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the Profile's {@code bootstrap} section into the system prompt: {@code text} (inline) and
 * {@code files} (paths that must pass the Sandbox read check). A bad file is skipped with a warning
 * so one wrong path does not take the agent down.
 *
 * <p>The exact bootstrap/skills file layout of the course is not in this repository (to confirm),
 * so only this minimal contract is implemented; {@code skills} are not loaded yet.
 */
public final class BootstrapContextLoader implements ContextLoader {
  static final int MAX_FILE_CHARS = 8000;
  private static final Logger LOG = LoggerFactory.getLogger(BootstrapContextLoader.class);
  private final Sandbox sandbox;

  public BootstrapContextLoader(Sandbox sandbox) {
    this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
  }

  @Override
  public String load(AgentProfile profile, AgentSession session) {
    Map<String, Object> bootstrap = profile.bootstrap();
    StringBuilder out = new StringBuilder();
    Object text = bootstrap.get("text");
    if (text instanceof String inline && !inline.isBlank()) {
      out.append(inline.strip());
    }
    if (bootstrap.get("files") instanceof Iterable<?> files) {
      for (Object entry : files) {
        String content = readFile(String.valueOf(entry));
        if (!content.isEmpty()) {
          out.append(out.length() == 0 ? "" : "\n").append(content);
        }
      }
    }
    return out.toString();
  }

  private String readFile(String name) {
    try {
      Path path = sandbox.checkPath(Path.of(name), Sandbox.Access.READ);
      if (!Files.isRegularFile(path)) {
        LOG.warn("Bootstrap file skipped (not a file): {}", name);
        return "";
      }
      try (InputStream in = Files.newInputStream(path)) {
        String text = new String(in.readNBytes(MAX_FILE_CHARS * 4), StandardCharsets.UTF_8).strip();
        return text.length() > MAX_FILE_CHARS ? text.substring(0, MAX_FILE_CHARS) : text;
      }
    } catch (SandboxViolation denied) {
      LOG.warn("Bootstrap file skipped (outside sandbox): {}", name);
    } catch (IOException | RuntimeException e) {
      LOG.warn("Bootstrap file skipped (unreadable): {}", name);
    }
    return "";
  }
}
