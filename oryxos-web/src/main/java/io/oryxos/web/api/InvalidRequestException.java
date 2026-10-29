package io.oryxos.web.api;

/**
 * A client mistake with a message we wrote ourselves (never an echo of caller input), so it is safe
 * to return as the 400 text.
 */
final class InvalidRequestException extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;

  InvalidRequestException(String message) {
    super(message);
  }
}
