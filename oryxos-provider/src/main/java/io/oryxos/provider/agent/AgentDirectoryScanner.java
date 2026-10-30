package io.oryxos.provider.agent;

import io.oryxos.core.profile.AgentDefinitionException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads every agent directory under the agents root. One broken directory never hides the others:
 * it is listed in {@link Result#problems()} with the reason, and the rest load. Directories that
 * are links, or whose real location is outside the root, are refused.
 */
public final class AgentDirectoryScanner {
  private static final Logger LOG = LoggerFactory.getLogger(AgentDirectoryScanner.class);

  /** What a scan found: the valid agents and, per directory name, why the others were refused. */
  public record Result(List<AgentDefinition> agents, Map<String, String> problems) {
    public Result {
      agents = List.copyOf(agents);
      problems = Collections.unmodifiableMap(new TreeMap<>(problems));
    }

    @Override
    public List<AgentDefinition> agents() {
      return List.copyOf(agents);
    }

    @Override
    public Map<String, String> problems() {
      return Collections.unmodifiableMap(new TreeMap<>(problems));
    }
  }

  private final Path root;
  private final AgentLoader loader;

  public AgentDirectoryScanner(Path root, AgentLoader loader) {
    this.root = Objects.requireNonNull(root, "root");
    this.loader = Objects.requireNonNull(loader, "loader");
  }

  public Path root() {
    return root;
  }

  public Result scan() {
    List<AgentDefinition> agents = new ArrayList<>();
    Map<String, String> problems = new LinkedHashMap<>();
    if (!Files.isDirectory(root)) {
      return new Result(agents, problems);
    }
    List<Path> children = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
      stream.forEach(children::add);
    } catch (IOException e) {
      problems.put(
          ".", "the agents directory cannot be listed (" + e.getClass().getSimpleName() + ")");
      return new Result(agents, problems);
    }
    children.sort(Path::compareTo);
    for (Path child : children) {
      String name = AgentLoader.nameOf(child);
      if (name.startsWith(".")
          || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(child)) {
        continue; // hidden entries and stray files are not agents
      }
      try {
        requireInside(child);
        agents.add(loader.load(child));
      } catch (AgentDefinitionException problem) {
        problems.put(name, problem.getMessage());
        LOG.error("Agent directory '{}' not loaded: {}", name, problem.getMessage());
      }
    }
    return new Result(agents, problems);
  }

  /** The directory must be a real directory directly inside the root, not a link out of it. */
  public void requireInside(Path agentDir) {
    String name = AgentLoader.nameOf(agentDir);
    try {
      if (Files.isSymbolicLink(agentDir)) {
        throw new AgentDefinitionException(
            "agents/" + name + ": a link is not accepted as an agent directory");
      }
      Path realRoot = root.toRealPath();
      Path realDir = agentDir.toRealPath();
      if (!Objects.equals(realDir.getParent(), realRoot)) {
        throw new AgentDefinitionException(
            "agents/" + name + ": the directory resolves to a place outside the agents directory");
      }
    } catch (IOException e) {
      throw new AgentDefinitionException("agents/" + name + ": the directory cannot be read");
    }
  }
}
