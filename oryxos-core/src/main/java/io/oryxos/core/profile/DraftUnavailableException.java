package io.oryxos.core.profile;

/** A draft cannot be produced because no usable provider or model is configured. */
public class DraftUnavailableException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public DraftUnavailableException(String message) {
    super(message);
  }
}
