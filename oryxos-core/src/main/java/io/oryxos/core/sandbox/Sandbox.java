package io.oryxos.core.sandbox;

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

  /** Only http(s) URLs whose host is allowlisted. */
  void checkUri(URI uri);
}
