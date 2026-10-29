package io.oryxos.core.sandbox;

/** A sandbox declaration is malformed or unsafe. It must stop startup, never be skipped. */
public final class SandboxConfigException extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;

  public SandboxConfigException(String message) {
    super(message);
  }
}
