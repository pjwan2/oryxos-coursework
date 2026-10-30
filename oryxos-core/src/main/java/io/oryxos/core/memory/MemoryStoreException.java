package io.oryxos.core.memory;

/** The backend could not read or write memory (I/O error, malformed file, unreachable service). */
public class MemoryStoreException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public MemoryStoreException(String message) {
    super(message);
  }

  public MemoryStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
