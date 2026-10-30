package io.oryxos.core.sandbox;

import io.oryxos.core.profile.AgentProfile;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * Application-level allowlist checks, run BEFORE any side effect. This is policy, not isolation: it
 * does not replace OS-level sandboxing (containers, separate users, seccomp).
 */
public interface Sandbox {
  enum Access {
    READ,
    WRITE
  }

  /** Returns the canonical path to use, or throws {@link SandboxViolation}. */
  Path checkPath(Path requested, Access access);

  /** Argv form only (no shell string); the executable must be allowlisted. */
  void checkCommand(List<String> argv);

  /** Only http(s) URLs whose host is allowlisted; IP literals need explicit approval. */
  void checkUri(URI uri);

  /**
   * Resolves the host and refuses loopback, private, link-local and cloud-metadata addresses unless
   * approved. Cannot defeat DNS rebinding between this check and the connection.
   */
  default void checkResolvedTarget(URI uri) {
    // Implementations that do not resolve names have nothing more to check.
  }

  /** What an HTTP tool calls before any request. */
  default void checkHttpTarget(URI uri) {
    checkUri(uri);
    checkResolvedTarget(uri);
  }

  /**
   * The sandbox that applies to this Profile. Implementations that hold per-Profile policy return a
   * scoped instance; a Profile that declares nothing gets one that denies everything. Tools must
   * call this first, with the Profile of the current call, never a remembered one.
   */
  default Sandbox forProfile(AgentProfile profile) {
    return this;
  }

  /**
   * Drops anything cached for the named agent. Called when its definition changed or it was
   * removed, so the next call is judged against the new definition only.
   */
  default void invalidate(String profileName) {
    // Implementations without per-Profile state have nothing to drop.
  }
}
