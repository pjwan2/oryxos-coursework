package io.oryxos.tool;

import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.sandbox.Sandbox;
import io.oryxos.core.sandbox.SandboxConfigException;
import io.oryxos.core.sandbox.SandboxSpec;
import io.oryxos.core.sandbox.SandboxViolation;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-Profile sandbox. A Profile declares what it needs under {@code settings.sandbox}; the
 * operator's configuration is the CEILING. The effective permission is the intersection, so a
 * Profile can never grant itself more than the operator approved, and a Profile that declares
 * nothing is denied everything: it inherits neither another agent's permissions nor a default.
 *
 * <p>The scoped sandbox is derived from the {@link AgentProfile} passed with each call, never from
 * a ThreadLocal or a remembered "current" profile, so it cannot leak between requests.
 *
 * <p>Used directly (without a Profile) every check is refused: fail closed.
 */
public final class ProfileSandbox implements Sandbox {
  private static final Logger LOG = LoggerFactory.getLogger(ProfileSandbox.class);

  private final SandboxPolicy ceiling;
  private final List<Path> ceilingRead;
  private final List<Path> ceilingWrite;
  private final Function<String, List<InetAddress>> resolver;
  private final Map<AgentProfile, Sandbox> scoped = new ConcurrentHashMap<>();

  public ProfileSandbox(SandboxPolicy ceiling) {
    this(ceiling, null);
  }

  /** {@code resolver} null means real DNS; tests inject a fixed one. */
  public ProfileSandbox(SandboxPolicy ceiling, Function<String, List<InetAddress>> resolver) {
    this.ceiling = Objects.requireNonNull(ceiling, "ceiling");
    this.resolver = resolver;
    this.ceilingRead = ceiling.readRoots().stream().map(AllowListSandbox::canonical).toList();
    this.ceilingWrite = ceiling.writeRoots().stream().map(AllowListSandbox::canonical).toList();
  }

  @Override
  public Sandbox forProfile(AgentProfile profile) {
    Objects.requireNonNull(profile, "profile");
    return scoped.computeIfAbsent(profile, this::build);
  }

  /**
   * Forgets every cached policy of the named agent. The cache key is the whole Profile value, so a
   * changed Profile is never judged by an old entry; this keeps the cache from filling up with the
   * superseded ones and makes the invalidation explicit and testable.
   */
  @Override
  public void invalidate(String profileName) {
    scoped.keySet().removeIf(profile -> profile.name().equals(profileName));
  }

  private Sandbox build(AgentProfile profile) {
    SandboxPolicy requested;
    try {
      requested =
          SandboxPolicy.fromSpec(
              SandboxSpec.parse(
                  profile.settings().get("sandbox"),
                  "profile '" + profile.name() + "' settings.sandbox"));
    } catch (SandboxConfigException invalid) {
      // ProfileLoader rejects these at startup; this is the backstop for a Profile built elsewhere.
      LOG.error("Invalid sandbox declaration, denying everything: {}", invalid.getMessage());
      requested = SandboxPolicy.denyAll();
    }
    SandboxPolicy effective = intersect(profile.name(), requested);
    return resolver == null
        ? new AllowListSandbox(effective)
        : new AllowListSandbox(effective, resolver);
  }

  private SandboxPolicy intersect(String profileName, SandboxPolicy requested) {
    List<String> dropped = new java.util.ArrayList<>();
    List<Path> read = withinCeiling(requested.readRoots(), ceilingRead, "read root", dropped);
    List<Path> write = withinCeiling(requested.writeRoots(), ceilingWrite, "write root", dropped);

    Map<String, CommandRule> commands = new LinkedHashMap<>();
    requested
        .commandRules()
        .forEach(
            (name, rule) -> {
              CommandRule approved = ceiling.commandRules().get(name);
              if (approved != null && approved.anyArguments()) {
                commands.put(name, rule);
              } else {
                dropped.add("command " + name);
              }
            });

    Set<String> hosts =
        requested.hosts().stream()
            .filter(
                host -> {
                  boolean ok = ceilingCovers(host);
                  if (!ok) {
                    dropped.add("host " + host);
                  }
                  return ok;
                })
            .collect(Collectors.toSet());

    boolean privateNetworks = requested.allowPrivateNetworks() && ceiling.allowPrivateNetworks();
    if (requested.allowPrivateNetworks() && !privateNetworks) {
      dropped.add("allow_private_networks");
    }
    if (!dropped.isEmpty()) {
      LOG.warn(
          "Profile '{}' asked for more than the operator allows; denied: {}", profileName, dropped);
    }
    return new SandboxPolicy(read, write, commands, hosts, privateNetworks);
  }

  private static List<Path> withinCeiling(
      List<Path> requested, List<Path> allowed, String what, List<String> dropped) {
    return requested.stream()
        .filter(
            root -> {
              Path real;
              try {
                real = AllowListSandbox.canonical(root);
              } catch (RuntimeException e) {
                dropped.add(what + " (unresolvable)");
                return false;
              }
              boolean ok = allowed.stream().anyMatch(real::startsWith);
              if (!ok) {
                dropped.add(what + " " + root);
              }
              return ok;
            })
        .toList();
  }

  /** Exact entry, or a wildcard entry that covers it (IP literals need an exact entry). */
  private boolean ceilingCovers(String host) {
    String wanted = host.toLowerCase(Locale.ROOT);
    for (String approved : ceiling.hosts()) {
      String rule = approved.toLowerCase(Locale.ROOT);
      if (rule.equals(wanted)) {
        return true;
      }
      if (rule.startsWith("*.")) {
        String suffix = rule.substring(1); // ".example.org"
        String subject = wanted.startsWith("*.") ? wanted.substring(1) : "." + wanted;
        if (subject.endsWith(suffix) && subject.length() > suffix.length()) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public Path checkPath(Path requested, Access access) {
    throw noProfile();
  }

  @Override
  public void checkCommand(List<String> argv) {
    throw noProfile();
  }

  @Override
  public void checkUri(URI uri) {
    throw noProfile();
  }

  @Override
  public void checkResolvedTarget(URI uri) {
    throw noProfile();
  }

  private static SandboxViolation noProfile() {
    return new SandboxViolation("No profile in scope; use forProfile(profile)");
  }
}
