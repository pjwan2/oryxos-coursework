package io.oryxos.tool;

import io.oryxos.core.sandbox.Sandbox;
import io.oryxos.core.sandbox.SandboxViolation;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Allowlist policy enforcement. This is policy inside the JVM, not isolation: see docs. What it
 * does guarantee is that every decision is made on CANONICAL data, never on the raw string:
 *
 * <ul>
 *   <li>paths are resolved through symlinks and junctions and compared as {@link Path}s, after
 *       rejecting forms whose meaning cannot be established reliably (UNC/device paths, alternate
 *       data streams, drive-relative paths, DOS device names, trailing dots/spaces);
 *   <li>URLs are judged by the parsed host, never by substring, and IP literals, numeric host
 *       tricks, loopback/private/link-local/metadata targets need explicit approval or are always
 *       refused;
 *   <li>commands are an executable name plus per-argument patterns, never a command string.
 * </ul>
 */
public final class AllowListSandbox implements Sandbox {
  private static final Pattern DOS_DEVICE =
      Pattern.compile("(?i)^(con|prn|aux|nul|conin\\$|conout\\$|com[1-9]|lpt[1-9])(\\..*)?$");
  private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern IPV4 =
      Pattern.compile(
          "(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})");

  /** "2130706433", "0x7f.1", "0177.0.0.1": numbers the OS would read as an address. */
  private static final Pattern NUMERIC_LABEL = Pattern.compile("0[xX][0-9a-fA-F]+|[0-9]+");

  private enum Kind {
    PUBLIC,
    PRIVATE,
    NEVER
  }

  private final List<Path> readRoots;
  private final List<Path> writeRoots;
  private final SandboxPolicy policy;
  private final Set<InetAddress> approvedAddresses = new HashSet<>();
  private final Function<String, List<InetAddress>> resolver;

  public AllowListSandbox(SandboxPolicy policy) {
    this(policy, AllowListSandbox::systemResolve);
  }

  /** The resolver is injectable so network tests never depend on real DNS. */
  public AllowListSandbox(SandboxPolicy policy, Function<String, List<InetAddress>> resolver) {
    this.policy = Objects.requireNonNull(policy, "policy");
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.readRoots = policy.readRoots().stream().map(AllowListSandbox::canonical).toList();
    this.writeRoots = policy.writeRoots().stream().map(AllowListSandbox::canonical).toList();
    for (String host : policy.hosts()) {
      String normalized = host.toLowerCase(Locale.ROOT);
      if (IPV4.matcher(normalized).matches() || normalized.contains(":")) {
        try {
          approvedAddresses.add(InetAddress.getByName(normalized));
        } catch (UnknownHostException e) {
          throw new IllegalArgumentException("Allowlisted IP literal is invalid: " + host, e);
        }
      }
    }
  }

  private static List<InetAddress> systemResolve(String host) {
    try {
      return List.of(InetAddress.getAllByName(host));
    } catch (UnknownHostException e) {
      return List.of();
    }
  }

  // ---------------------------------------------------------------- files

  @Override
  public Path checkPath(Path requested, Access access) {
    Objects.requireNonNull(requested, "requested");
    rejectUnreliableForms(requested);
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

  /**
   * Refuses path spellings whose real target cannot be established reliably on every platform.
   * Refusing is the intended outcome: an unverifiable path is not allowed.
   */
  private static void rejectUnreliableForms(Path requested) {
    Path root = requested.getRoot();
    if (root != null) {
      String rootText = root.toString();
      if (rootText.startsWith("\\\\") || rootText.startsWith("//")) {
        throw new SandboxViolation("UNC and device paths are not allowed");
      }
      if (!requested.isAbsolute()) {
        throw new SandboxViolation("Drive-relative paths are not allowed");
      }
    }
    for (Path element : requested) {
      String name = element.toString();
      if (name.equals(".") || name.equals("..")) {
        continue;
      }
      if (name.indexOf(':') >= 0) {
        throw new SandboxViolation("Alternate data streams and ':' in names are not allowed");
      }
      if (name.indexOf('\\') >= 0 || name.matches(".*[<>|\"?*].*")) {
        throw new SandboxViolation("Name contains characters that are unsafe on Windows");
      }
      if (name.endsWith(".") || name.endsWith(" ")) {
        throw new SandboxViolation("Names ending in '.' or space are not allowed");
      }
      if (DOS_DEVICE.matcher(name).matches()) {
        throw new SandboxViolation("Reserved device names are not allowed");
      }
    }
  }

  /** Real path of the longest existing ancestor plus the not-yet-existing remainder. */
  static Path canonical(Path path) {
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

  // ------------------------------------------------------------- commands

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
    CommandRule rule = hasPath ? null : policy.commandRules().get(executable);
    if (rule == null) {
      throw new SandboxViolation("Command not allowed");
    }
    if (!rule.permits(argv.subList(1, argv.size()))) {
      throw new SandboxViolation("Arguments not allowed for this command");
    }
  }

  // ----------------------------------------------------------------- http

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
    String host = normalizeHost(uri.getHost());
    InetAddress literal = literal(host);
    if (literal != null) {
      checkLiteral(host, literal);
      return;
    }
    if (isAmbiguousNumeric(host)) {
      throw new SandboxViolation("Ambiguous numeric host is not allowed");
    }
    for (String label : host.split("\\.", -1)) {
      if (!LABEL.matcher(label).matches()) {
        throw new SandboxViolation("Invalid host name");
      }
    }
    if (!hostnameAllowed(host)) {
      throw new SandboxViolation("Host not allowed: " + host);
    }
  }

  @Override
  public void checkResolvedTarget(URI uri) {
    String host = normalizeHost(Objects.requireNonNull(uri, "uri").getHost());
    if (literal(host) != null) {
      return; // already classified by checkUri
    }
    List<InetAddress> addresses = resolver.apply(host);
    if (addresses.isEmpty()) {
      throw new SandboxViolation("Host cannot be resolved, so it cannot be verified: " + host);
    }
    for (InetAddress address : addresses) {
      Kind kind = classify(address);
      if (kind == Kind.NEVER) {
        throw new SandboxViolation("Host resolves to a forbidden address range: " + host);
      }
      if (kind == Kind.PRIVATE && !policy.allowPrivateNetworks()) {
        throw new SandboxViolation("Host resolves to a private or loopback address: " + host);
      }
    }
  }

  private void checkLiteral(String host, InetAddress address) {
    if (classify(address) == Kind.NEVER) {
      throw new SandboxViolation(
          "This IP range (link-local, metadata, multicast) is never allowed");
    }
    // An IP literal is only accepted when that exact address was approved by name.
    if (!approvedAddresses.contains(address)) {
      throw new SandboxViolation("IP address not approved: " + host);
    }
  }

  private boolean hostnameAllowed(String host) {
    for (String allowed : policy.hosts()) {
      String rule = allowed.toLowerCase(Locale.ROOT);
      if (host.equals(rule)) {
        return true;
      }
      if (rule.startsWith("*.") && host.endsWith(rule.substring(1))) {
        return true;
      }
    }
    return false;
  }

  /** Lower case, no IPv6 brackets or zone id, one trailing dot removed; ASCII only. */
  private static String normalizeHost(String raw) {
    if (raw == null) {
      throw new SandboxViolation("URL has no valid host");
    }
    String host = raw.toLowerCase(Locale.ROOT);
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    int zone = host.indexOf('%');
    if (zone >= 0) {
      host = host.substring(0, zone);
    }
    if (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    for (int i = 0; i < host.length(); i++) {
      if (host.charAt(i) > 0x7e || host.charAt(i) <= 0x20) {
        throw new SandboxViolation("Only ASCII host names are supported");
      }
    }
    if (host.isEmpty()) {
      throw new SandboxViolation("URL has no valid host");
    }
    return host;
  }

  /** The address if the host is a canonical IPv4/IPv6 literal (never triggers DNS). */
  private static InetAddress literal(String host) {
    boolean ipv6 = host.indexOf(':') >= 0;
    if (!ipv6 && !IPV4.matcher(host).matches()) {
      return null;
    }
    if (!ipv6) {
      for (String octet : host.split("\\.")) {
        if (Integer.parseInt(octet) > 255) {
          throw new SandboxViolation("Invalid IP address");
        }
      }
    }
    try {
      return InetAddress.getByName(host);
    } catch (UnknownHostException e) {
      throw new SandboxViolation("Invalid IP address");
    }
  }

  private static boolean isAmbiguousNumeric(String host) {
    String[] labels = host.split("\\.", -1);
    if (labels.length > 4) {
      return false;
    }
    for (String label : labels) {
      if (!NUMERIC_LABEL.matcher(label).matches()) {
        return false;
      }
    }
    return true;
  }

  private static Kind classify(InetAddress address) {
    byte[] b = address.getAddress();
    if (address.isAnyLocalAddress()
        || address.isMulticastAddress()
        || address.isLinkLocalAddress()) {
      return Kind.NEVER;
    }
    if (b.length == 4) {
      int a = b[0] & 0xff;
      int c = b[1] & 0xff;
      if (a == 0) {
        return Kind.NEVER;
      }
      if (a == 100 && c == 100 && (b[2] & 0xff) == 100 && (b[3] & 0xff) == 200) {
        return Kind.NEVER; // Alibaba Cloud metadata
      }
      boolean cgnat = a == 100 && c >= 64 && c <= 127;
      boolean reserved = a >= 240 || (a == 192 && c == 0) || (a == 198 && (c == 18 || c == 19));
      if (address.isLoopbackAddress() || address.isSiteLocalAddress() || cgnat || reserved) {
        return Kind.PRIVATE;
      }
      return Kind.PUBLIC;
    }
    // IPv6: AWS IMDS over IPv6 is fd00:ec2::254, which sits in the unique-local range.
    if ((b[0] & 0xff) == 0xfd && b[1] == 0x00 && b[2] == 0x0e && b[3] == (byte) 0xc2) {
      return Kind.NEVER;
    }
    boolean uniqueLocal = (b[0] & 0xfe) == 0xfc;
    boolean siteLocal = (b[0] & 0xff) == 0xfe && (b[1] & 0xc0) == 0xc0;
    if (address.isLoopbackAddress() || uniqueLocal || siteLocal) {
      return Kind.PRIVATE;
    }
    return Kind.PUBLIC;
  }
}
