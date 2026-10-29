package io.oryxos.core.agent;

/**
 * The model provider could not be reached or is overloaded (connection refused, 5xx, 429). The
 * provider's own message is deliberately not carried: it may contain request details or secrets.
 */
public final class ModelUnavailableException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ModelUnavailableException(String providerName) {
    super("Model provider unavailable: " + providerName);
  }
}
