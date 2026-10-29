package io.oryxos.tool;

import io.oryxos.core.sandbox.Sandbox;
import io.oryxos.core.sandbox.SandboxViolation;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Allowlist policy enforcement. Paths are canonicalised through symlinks before comparison so that
 * "..", relative paths and links cannot escape a root. This is application-level policy only.
 */
public final class AllowListSandbox implements Sandbox {
  private final List<Path> readRoots;
  private final List<Path> writeRoots;
  private final SandboxPolicy policy;

  public AllowListSandbox(SandboxPolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy");
    this.readRoots = policy.readRoots().stream().map(AllowListSandbox::canonical).toList();
    this.writeRoots = policy.writeRoots().stream().map(AllowListSandbox::canonical).toList();
  }

  @Override
  public Path checkPath(Path requested, Access access) {
    Objects.requireNonNull(requested, "requested");
    Path real;
    try {
      real = canonical(requested);
    } catch (InvalidPathException | IllegalStateException e) {
      throw new SandboxViolation("Invalid path");
    }
    List<Path> roots = access == Access.WRITE ? writeRoots : readRoots;
    for (Path root : roots) {
      if (real.startsWith(root)) {
        return real;
      }
    }
    throw new SandboxViolation("Path outside allowed " + access + " roots");
  }

  @Override
  public void checkCommand(List<String> argv) {
    if (argv == null || argv.isEmpty()) {
      throw new SandboxViolation("Empty command");
    }
    for (String part : argv) {
      if (part == null || part.indexOf('\0') >= 0) {
        throw new SandboxViolation("Invalid command argument");
      }
    }
    String executable = argv.getFirst();
    // Bare names only: a path such as ./git or C:\x\git.exe would bypass the allowlist.
    boolean hasPath = executable.contains("/") || executable.contains("\\");
    if (hasPath || !policy.commands().contains(executable)) {
      throw new SandboxViolation("Command not allowed");
    }
  }

  @Override
  public void checkUri(URI uri) {
    Objects.requireNonNull(uri, "uri");
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!"http".equals(scheme) && !"https".equals(scheme)) {
      throw new SandboxViolation("Only http(s) is allowed");
    }
    // user@host tricks (http://allowed.com@evil.com) are refused outright.
    if (uri.getUserInfo() != null || uri.getHost() == null) {
      throw new SandboxViolation("URL has no valid host");
    }
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    for (String allowed : policy.hosts()) {
      String rule = allowed.toLowerCase(Locale.ROOT);
      boolean wildcard = rule.startsWith("*.") && host.endsWith(rule.substring(1));
      if (host.equals(rule) || wildcard) {
        return;
      }
    }
    throw new SandboxViolation("Host not allowed");
  }

  /** Real path of the longest existing ancestor plus the not-yet-existing remainder. */
  private static Path canonical(Path path) {
    Path absolute = path.toAbsolutePath().normalize();
    Path existing = absolute;
    while (existing != null && !Files.exists(existing)) {
      existing = existing.getParent();
    }
    if (existing == null) {
      return absolute;
    }
    try {
      return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot resolve path", e);
    }
  }
}
