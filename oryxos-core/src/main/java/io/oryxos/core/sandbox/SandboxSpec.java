package io.oryxos.core.sandbox;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What a Profile (or the operator) asks the sandbox to allow, as validated plain data. Anything not
 * declared is denied. Parsing is strict: unknown keys, wrong types and over-broad entries are
 * rejected with a {@link SandboxConfigException} instead of being ignored, because a silently
 * ignored security setting is worse than a startup failure.
 *
 * <pre>
 * sandbox:
 *   read_roots: [".oryxos/workspace/weather"]
 *   write_roots: []
 *   hosts: ["api.open-meteo.com", "*.example.org"]
 *   allow_private_networks: false
 *   commands:
 *     - git                         # program only: no arguments allowed
 *     - name: git
 *       args: ["status", "log", "-n", "[0-9]{1,3}"]   # every argument must fully match one entry
 *       max_args: 6
 * </pre>
 */
public record SandboxSpec(
    List<String> readRoots,
    List<String> writeRoots,
    List<CommandSpec> commands,
    List<String> hosts,
    boolean allowPrivateNetworks) {

  static final int MAX_ENTRIES = 100;
  static final int DEFAULT_MAX_ARGS = 16;
  private static final Set<String> KEYS =
      Set.of("read_roots", "write_roots", "commands", "hosts", "allow_private_networks");
  private static final Pattern PROGRAM = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");
  private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern IPV4 = Pattern.compile("(\\d{1,3}\\.){3}\\d{1,3}");
  private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-f:.]{2,45}");

  /** An argument pattern that accepts all of these is not a rule, it is "anything". */
  private static final List<String> PROBES = List.of("zzzz", "--evil=1", "a b; c", "../../x");

  /** One allowed program. {@code args} empty means the program may run with no arguments only. */
  public record CommandSpec(String name, List<String> args, int maxArgs) {
    public CommandSpec {
      args = List.copyOf(args);
    }

    @Override
    public List<String> args() {
      return List.copyOf(args);
    }
  }

  public SandboxSpec {
    readRoots = List.copyOf(readRoots);
    writeRoots = List.copyOf(writeRoots);
    commands = List.copyOf(commands);
    hosts = List.copyOf(hosts);
  }

  @Override
  public List<String> readRoots() {
    return List.copyOf(readRoots);
  }

  @Override
  public List<String> writeRoots() {
    return List.copyOf(writeRoots);
  }

  @Override
  public List<CommandSpec> commands() {
    return List.copyOf(commands);
  }

  @Override
  public List<String> hosts() {
    return List.copyOf(hosts);
  }

  public static SandboxSpec denyAll() {
    return new SandboxSpec(List.of(), List.of(), List.of(), List.of(), false);
  }

  /** {@code raw} is the YAML/properties value; null means "nothing declared" = deny all. */
  public static SandboxSpec parse(Object raw, String where) {
    if (raw == null) {
      return denyAll();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new SandboxConfigException(where + " must be a mapping");
    }
    for (Object key : map.keySet()) {
      if (!(key instanceof String name) || !KEYS.contains(name)) {
        throw new SandboxConfigException(
            where + ": unknown key '" + key + "' (allowed: " + new java.util.TreeSet<>(KEYS) + ")");
      }
    }
    return new SandboxSpec(
        roots(map.get("read_roots"), where + ".read_roots"),
        roots(map.get("write_roots"), where + ".write_roots"),
        commands(map.get("commands"), where + ".commands"),
        hosts(map.get("hosts"), where + ".hosts"),
        flag(map.get("allow_private_networks"), where + ".allow_private_networks"));
  }

  private static boolean flag(Object value, String where) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    throw new SandboxConfigException(where + " must be true or false");
  }

  private static List<?> list(Object value, String where) {
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> items)) {
      throw new SandboxConfigException(where + " must be a list");
    }
    if (items.size() > MAX_ENTRIES) {
      throw new SandboxConfigException(where + " has more than " + MAX_ENTRIES + " entries");
    }
    return items;
  }

  private static String text(Object value, String where) {
    if (!(value instanceof String s) || s.isBlank()) {
      throw new SandboxConfigException(where + " must be a non-blank string");
    }
    if (s.indexOf('\0') >= 0) {
      throw new SandboxConfigException(where + " contains a NUL character");
    }
    return s.strip();
  }

  private static List<String> roots(Object value, String where) {
    List<String> result = new ArrayList<>();
    List<?> items = list(value, where);
    for (int i = 0; i < items.size(); i++) {
      String at = where + "[" + i + "]";
      String root = text(items.get(i), at);
      if (root.startsWith("\\\\") || root.startsWith("//")) {
        throw new SandboxConfigException(at + ": UNC and device paths are not supported");
      }
      Path path;
      try {
        path = Path.of(root);
      } catch (InvalidPathException e) {
        throw new SandboxConfigException(at + ": not a valid path");
      }
      if (path.toAbsolutePath().normalize().getFileName() == null) {
        throw new SandboxConfigException(at + ": a filesystem root cannot be allowed as a whole");
      }
      result.add(root);
    }
    return result;
  }

  private static List<String> hosts(Object value, String where) {
    List<String> result = new ArrayList<>();
    List<?> items = list(value, where);
    for (int i = 0; i < items.size(); i++) {
      String at = where + "[" + i + "]";
      String host = text(items.get(i), at).toLowerCase(Locale.ROOT);
      if (host.startsWith("*.")) {
        String base = host.substring(2);
        if (!base.contains(".") || !validHostname(base)) {
          throw new SandboxConfigException(
              at + ": wildcard needs a domain with at least two labels, e.g. *.example.org");
        }
      } else if (host.contains("*")) {
        throw new SandboxConfigException(at + ": '*' is only allowed as a leading '*.' label");
      } else if (!validHostname(host) && !IPV4.matcher(host).matches() && !validIpv6(host)) {
        throw new SandboxConfigException(at + ": not a valid host name or IP address");
      }
      result.add(host);
    }
    return result;
  }

  private static boolean validIpv6(String host) {
    return host.contains(":") && IPV6_CHARS.matcher(host).matches();
  }

  private static boolean validHostname(String host) {
    if (host.length() > 253) {
      return false;
    }
    for (String label : host.split("\\.", -1)) {
      if (!LABEL.matcher(label).matches()) {
        return false;
      }
    }
    return true;
  }

  private static List<CommandSpec> commands(Object value, String where) {
    List<CommandSpec> result = new ArrayList<>();
    List<?> items = list(value, where);
    for (int i = 0; i < items.size(); i++) {
      String at = where + "[" + i + "]";
      Object item = items.get(i);
      if (item instanceof String name) {
        result.add(new CommandSpec(program(name, at), List.of(), 0));
      } else if (item instanceof Map<?, ?> map) {
        for (Object key : map.keySet()) {
          if (!"name".equals(key) && !"args".equals(key) && !"max_args".equals(key)) {
            throw new SandboxConfigException(at + ": unknown key '" + key + "'");
          }
        }
        List<String> args = new ArrayList<>();
        List<?> patterns = list(map.get("args"), at + ".args");
        for (int j = 0; j < patterns.size(); j++) {
          args.add(argPattern(patterns.get(j), at + ".args[" + j + "]"));
        }
        int max = DEFAULT_MAX_ARGS;
        if (map.get("max_args") != null) {
          if (!(map.get("max_args") instanceof Integer n) || n < 0 || n > 64) {
            throw new SandboxConfigException(at + ".max_args must be an integer 0..64");
          }
          max = n;
        }
        result.add(new CommandSpec(program(map.get("name"), at + ".name"), args, max));
      } else {
        throw new SandboxConfigException(at + " must be a program name or a mapping");
      }
    }
    return result;
  }

  private static String program(Object value, String where) {
    String name = text(value, where);
    if (!PROGRAM.matcher(name).matches()) {
      throw new SandboxConfigException(
          where + ": use a bare program name (no path, spaces or shell characters)");
    }
    return name;
  }

  private static String argPattern(Object value, String where) {
    String regex = text(value, where);
    if (regex.length() > 200) {
      throw new SandboxConfigException(where + ": pattern too long");
    }
    Pattern compiled;
    try {
      compiled = Pattern.compile(regex);
    } catch (PatternSyntaxException e) {
      throw new SandboxConfigException(where + ": not a valid regular expression");
    }
    boolean matchesEverything = true;
    for (String probe : PROBES) {
      matchesEverything &= compiled.matcher(probe).matches();
    }
    if (matchesEverything) {
      throw new SandboxConfigException(where + ": pattern accepts any argument; be specific");
    }
    return regex;
  }
}
