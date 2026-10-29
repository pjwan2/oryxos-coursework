package io.oryxos.tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Allowlists for the sandbox. Everything not listed is denied; the default lists nothing. */
public record SandboxPolicy(
    List<Path> readRoots, List<Path> writeRoots, Set<String> commands, Set<String> hosts) {
  public SandboxPolicy {
    readRoots = List.copyOf(readRoots);
    writeRoots = List.copyOf(writeRoots);
    commands = Set.copyOf(commands);
    hosts = Set.copyOf(hosts);
  }

  public static SandboxPolicy denyAll() {
    return new SandboxPolicy(List.of(), List.of(), Set.of(), Set.of());
  }
}
