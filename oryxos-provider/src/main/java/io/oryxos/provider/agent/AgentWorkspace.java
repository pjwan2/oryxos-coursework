package io.oryxos.provider.agent;

import io.oryxos.core.profile.AgentFile;
import io.oryxos.core.workspace.WorkspaceEntry;
import io.oryxos.core.workspace.WorkspaceFile;
import io.oryxos.core.workspace.WorkspaceFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The files of the registered AGENT.md agents, and nothing else: not the legacy profiles, not the
 * database, not the application configuration. Every path goes through {@link WorkspacePaths}, text
 * file types only, size limits both ways. Saving an AGENT.md (or any other file of an agent) goes
 * through the lifecycle service, so it is validated, registered and rolled back like any other
 * change.
 */
public final class AgentWorkspace implements WorkspaceFiles {
  static final int MAX_ENTRIES = 500;
  static final int MAX_READ_BYTES = 1024 * 1024;
  private static final int TREE_DEPTH = 4;

  private final AgentLifecycleService lifecycle;

  public AgentWorkspace(AgentLifecycleService lifecycle) {
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
  }

  @Override
  public List<WorkspaceEntry> tree() {
    Path root = lifecycle.root();
    List<WorkspaceEntry> entries = new ArrayList<>();
    if (!Files.isDirectory(root)) {
      return entries;
    }
    for (String name : lifecycle.agentNames()) {
      Path dir = root.resolve(name);
      if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      try (Stream<Path> walk = Files.walk(dir, TREE_DEPTH)) {
        List<Path> found =
            walk.filter(p -> visible(dir, p)).sorted(Comparator.comparing(Path::toString)).toList();
        for (Path path : found) {
          if (entries.size() >= MAX_ENTRIES) {
            return entries;
          }
          boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
          entries.add(
              new WorkspaceEntry(
                  root.relativize(path).toString().replace('\\', '/'),
                  directory,
                  directory ? 0 : Files.size(path)));
        }
      } catch (IOException | UncheckedIOException e) {
        continue; // an unreadable agent directory is left out of the tree
      }
    }
    return entries;
  }

  private static boolean visible(Path agentDir, Path path) {
    if (!WorkspacePaths.plain(agentDir, path)) {
      return false;
    }
    Path relative = agentDir.relativize(path);
    for (Path part : relative) {
      if (part.toString().startsWith(".")) {
        return false;
      }
    }
    return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
  }

  @Override
  public WorkspaceFile read(String path) {
    Target target = target(path);
    WorkspacePaths.requireTextType(target.leaf());
    if (!Files.isRegularFile(target.file(), LinkOption.NOFOLLOW_LINKS)) {
      throw WorkspacePaths.refused("there is no such file");
    }
    try {
      long size = Files.size(target.file());
      if (size > MAX_READ_BYTES) {
        throw WorkspacePaths.refused("the file is larger than " + MAX_READ_BYTES + " bytes");
      }
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(Files.readAllBytes(target.file())))
              .toString();
      return new WorkspaceFile(target.relative(), text, size, editable(target));
    } catch (CharacterCodingException e) {
      throw WorkspacePaths.refused("the file is not valid UTF-8 text");
    } catch (IOException e) {
      throw WorkspacePaths.refused("the file cannot be read");
    }
  }

  @Override
  public WorkspaceFile save(String path, String content) {
    Objects.requireNonNull(content, "content");
    Target target = target(path);
    WorkspacePaths.requireTextType(target.leaf());
    if (!editable(target)) {
      throw WorkspacePaths.refused("this file is not edited through the workspace");
    }
    if (target.segments().size() < 2) {
      throw WorkspacePaths.refused("files belong inside an agent directory");
    }
    if (AgentLoader.FILE_NAME.equals(target.leaf()) && target.segments().size() == 2) {
      lifecycle.update(target.agent(), content);
    } else {
      String inside = String.join("/", target.segments().subList(1, target.segments().size()));
      lifecycle.saveFiles(target.agent(), List.of(new AgentFile(inside, content)));
    }
    return read(path);
  }

  private record Target(String agent, List<String> segments, Path file, String relative) {
    String leaf() {
      return segments.getLast();
    }
  }

  private Target target(String path) {
    List<String> segments = WorkspacePaths.segments(path);
    String agent = segments.getFirst();
    if (lifecycle.definition(agent).isEmpty()) {
      throw WorkspacePaths.refused("only the directories of registered agents are available");
    }
    Path file = WorkspacePaths.resolve(lifecycle.root(), segments);
    return new Target(agent, segments, file, String.join("/", segments));
  }

  private static boolean editable(Target target) {
    boolean memory = AgentLoader.MEMORY_FILE.equals(target.leaf()) && target.segments().size() == 2;
    boolean typeOk = WorkspacePaths.TEXT_TYPES.contains(WorkspacePaths.extension(target.leaf()));
    return typeOk && !memory && target.segments().size() >= 2;
  }
}
