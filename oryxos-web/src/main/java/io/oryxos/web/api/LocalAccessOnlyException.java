package io.oryxos.web.api;

/** A management write came from somewhere other than the local machine. */
public class LocalAccessOnlyException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public LocalAccessOnlyException() {
    super("Management writes are limited to local access");
  }
}
