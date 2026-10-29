package io.oryxos.storage.migration;

/**
 * The database could not be brought to the schema this version needs. Startup must stop. The
 * message is self-contained (it already carries the underlying SQLite reason) and there is no cause
 * chain on purpose: the command line prints the root cause, which must be this sentence.
 */
public class MigrationException extends IllegalStateException {
  private static final long serialVersionUID = 1L;

  public MigrationException(String message) {
    super(message);
  }
}
