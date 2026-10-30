package io.oryxos.provider.agent;

import io.oryxos.core.workspace.WorkspacePathException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The path rules of the workspace API. A request path is relative to the agents directory and is
 * split on {@code /}; anything that could mean something else to the operating system is refused
 * instead of being cleaned up: absolute paths, drive letters and alternate data streams ({@code
 * :}), backslashes, {@code ..}, hidden names, Windows device names. Then the real location is
 * checked: no link or junction on the way may lead out of the agents directory.
 */
final class WorkspacePaths {
  static final int MAX_PATH_CHARS = 300;
  static final int MAX_SEGMENT_CHARS = 100;
  static final int MAX_DEPTH = 5;

  /** Text types the API reads and writes. Nothing executable by itself, no databases or keys. */
  static final Set<String> TEXT_TYPES =
      Set.of("md", "txt", "yaml", "yml", "json", "csv", "py", "sh");

  private static final Pattern DEVICE =
      Pattern.compile("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?");
  private static final Pattern SECRET_LOOKING =
      Pattern.compile(
          "(?i).*(secret|password|passwd|credential|token|api[-_]?key|private[-_]?key).*");
  private static final Pattern CONFIG_LOOKING =
      Pattern.compile("(?i)application.*\\.(ya?ml|properties)");

  private WorkspacePaths() {}

  /** The validated segments of a relative path (no filesystem access). */
  static List<String> segments(String relative) {
    if (relative == null || relative.isBlank()) {
      throw refused("a path is required");
    }
    if (relative.length() > MAX_PATH_CHARS) {
      throw refused("the path is too long");
    }
    for (int i = 0; i < relative.length(); i++) {
      char c = relative.charAt(i);
      // '%' is refused too: a path that still contains an escape after decoding was double-encoded.
      if (c == '\\' || c == ':' || c == '%' || c < 0x20 || c == 0x7f) {
        throw refused("the path contains a character that is not allowed");
      }
    }
    if (relative.startsWith("/")) {
      throw refused("the path must be relative to the agents directory");
    }
    String[] parts = relative.split("/", -1);
    if (parts.length > MAX_DEPTH) {
      throw refused("the path is nested too deeply");
    }
    for (String part : parts) {
      if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
        throw refused("the path must not contain empty, '.' or '..' segments");
      }
      if (part.length() > MAX_SEGMENT_CHARS) {
        throw refused("a path segment is too long");
      }
      if (part.startsWith(".")) {
        throw refused("hidden files and directories are not available");
      }
      if (part.endsWith(".") || part.endsWith(" ") || DEVICE.matcher(part).matches()) {
        throw refused("the path contains a name the operating system treats specially");
      }
    }
    return List.of(parts);
  }

  /**
   * The absolute path for {@code segments} under {@code root}, after checking that none of the
   * existing parts is a link and that the real location stays inside the real root.
   */
  static Path resolve(Path root, List<String> segments) {
    Path realRoot;
    try {
      realRoot = root.toRealPath();
    } catch (IOException e) {
      throw refused("the agents directory is not available");
    }
    Path current = realRoot;
    for (String segment : segments) {
      current = current.resolve(segment);
      if (Files.isSymbolicLink(current)) {
        throw refused("links are not followed");
      }
      if (Files.exists(current)) {
        try {
          if (!current.toRealPath().startsWith(realRoot)) {
            throw refused("the path leads outside the agents directory");
          }
        } catch (IOException e) {
          throw refused("the path cannot be resolved");
        }
      }
    }
    if (!current.normalize().startsWith(realRoot)) {
      throw refused("the path leads outside the agents directory");
    }
    return current;
  }

  /**
   * True when {@code path} lies inside {@code base} and no part of it, from the first segment on,
   * is a symbolic link, a Windows junction or any other reparse point. A walk that stays on such
   * paths can never be led out of the directory it started in.
   */
  static boolean plain(Path base, Path path) {
    Path relative = base.relativize(path);
    Path current = base;
    for (Path part : relative) {
      current = current.resolve(part);
      try {
        java.nio.file.attribute.BasicFileAttributes attributes =
            Files.readAttributes(
                current,
                java.nio.file.attribute.BasicFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()) {
          return false;
        }
      } catch (IOException e) {
        return false;
      }
    }
    return true;
  }

  static String extension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  static void requireTextType(String fileName) {
    if (!TEXT_TYPES.contains(extension(fileName))) {
      throw refused("only these file types are available: " + new java.util.TreeSet<>(TEXT_TYPES));
    }
    if (SECRET_LOOKING.matcher(fileName).matches() || CONFIG_LOOKING.matcher(fileName).matches()) {
      throw refused("files that look like secrets or application configuration are not available");
    }
  }

  static WorkspacePathException refused(String why) {
    return new WorkspacePathException("Workspace path refused: " + why);
  }
}
