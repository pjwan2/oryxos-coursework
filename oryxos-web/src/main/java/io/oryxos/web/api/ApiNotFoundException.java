package io.oryxos.web.api;

/** The thing asked for does not exist. The label is ours ("Session"), never the caller's input. */
final class ApiNotFoundException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  ApiNotFoundException(String what) {
    super(what + " not found");
  }
}
