package io.oryxos.tool;

import io.oryxos.core.sandbox.SandboxSpec;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Allowlists for the sandbox. Everything not listed is denied; the default lists nothing. */
public record SandboxPolicy(
    List<Path> readRoots,
    List<Path> writeRoots,
    Map<String, CommandRule> commandRules,
    Set<String> hosts,
    boolean allowPrivateNetworks) {
  public SandboxPolicy {
    readRoots = List.copyOf(readRoots);
    writeRoots = List.copyOf(writeRoots);
    commandRules = Map.copyOf(commandRules);
    hosts = Set.copyOf(hosts);
  }

  /** Programs allowed with any arguments, public networks only. */
  public SandboxPolicy(
      List<Path> readRoots, List<Path> writeRoots, Set<String> commands, Set<String> hosts) {
    this(readRoots, writeRoots, anyArgs(commands), hosts, false);
  }

  private static Map<String, CommandRule> anyArgs(Set<String> commands) {
    Map<String, CommandRule> rules = new LinkedHashMap<>();
    commands.forEach(name -> rules.put(name, CommandRule.unrestricted()));
    return rules;
  }

  public Set<String> commands() {
    return commandRules.keySet();
  }

  public static SandboxPolicy denyAll() {
    return new SandboxPolicy(List.of(), List.of(), Map.of(), Set.of(), false);
  }

  /**
   * The operator's ceiling: which programs may be used at all (argument rules belong to Profiles).
   */
  public static SandboxPolicy ceilingFromSpec(SandboxSpec spec) {
    SandboxPolicy base = fromSpec(spec);
    Map<String, CommandRule> programs = new LinkedHashMap<>();
    base.commandRules().keySet().forEach(name -> programs.put(name, CommandRule.unrestricted()));
    return new SandboxPolicy(
        base.readRoots(), base.writeRoots(), programs, base.hosts(), base.allowPrivateNetworks());
  }

  /** From an already validated spec; commands without patterns get "no arguments". */
  public static SandboxPolicy fromSpec(SandboxSpec spec) {
    Map<String, CommandRule> rules = new LinkedHashMap<>();
    for (SandboxSpec.CommandSpec command : spec.commands()) {
      rules.put(
          command.name(),
          command.args().isEmpty()
              ? CommandRule.noArguments()
              : CommandRule.matching(command.args(), command.maxArgs()));
    }
    return new SandboxPolicy(
        spec.readRoots().stream().map(Path::of).toList(),
        spec.writeRoots().stream().map(Path::of).toList(),
        rules,
        Set.copyOf(spec.hosts()),
        spec.allowPrivateNetworks());
  }
}
