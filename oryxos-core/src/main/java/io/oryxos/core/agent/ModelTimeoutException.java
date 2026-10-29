package io.oryxos.core.agent;

/** The model provider did not answer in time. Carries no provider message, see sibling class. */
public final class ModelTimeoutException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ModelTimeoutException(String providerName) {
    super("Model provider timed out: " + providerName);
  }
}
