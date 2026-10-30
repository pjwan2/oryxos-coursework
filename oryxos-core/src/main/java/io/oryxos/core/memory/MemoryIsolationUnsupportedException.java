package io.oryxos.core.memory;

/**
 * The configured backend cannot keep one agent's memory apart from the others, so it refuses
 * instead of quietly sharing it. The text names the backend and what to do.
 */
public final class MemoryIsolationUnsupportedException extends MemoryStoreException {
  private static final long serialVersionUID = 1L;

  public MemoryIsolationUnsupportedException(String message) {
    super(message);
  }
}
