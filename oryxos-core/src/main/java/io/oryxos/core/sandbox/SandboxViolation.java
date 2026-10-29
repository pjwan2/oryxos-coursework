package io.oryxos.core.sandbox;

/** Thrown when an operation is outside the configured allowlist. */
public final class SandboxViolation extends SecurityException {
  private static final long serialVersionUID = 1L;

  public SandboxViolation(String message) {
    super(message);
  }
}
