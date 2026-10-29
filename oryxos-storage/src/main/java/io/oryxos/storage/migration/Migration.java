package io.oryxos.storage.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One numbered schema change made of steps. A step is either plain SQL or a guarded action such as
 * "add this column unless it is already there", so a step can be repeated against a database in any
 * historical state. The checksum covers every step, so editing a migration that was already applied
 * somewhere is detected at startup instead of being silently ignored.
 */
public final class Migration {
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  /** SQL work done on the migration's connection, inside the migration's transaction. */
  @FunctionalInterface
  public interface Action {
    void run(Connection connection) throws SQLException;
  }

  /** A fingerprint (what the checksum covers) and the work it stands for. */
  public record Step(String fingerprint, Action action) {
    public Step {
      Objects.requireNonNull(fingerprint, "fingerprint");
      Objects.requireNonNull(action, "action");
    }
  }

  private final int version;
  private final String description;
  private final List<Step> steps;

  public Migration(int version, String description, List<Step> steps) {
    if (version < 1) {
      throw new IllegalArgumentException("Migration versions start at 1");
    }
    this.version = version;
    this.description = Objects.requireNonNull(description, "description");
    this.steps = List.copyOf(steps);
  }

  public int version() {
    return version;
  }

  public String description() {
    return description;
  }

  public List<Step> steps() {
    return List.copyOf(steps);
  }

  /** Stable fingerprint of what this migration does. */
  public String checksum() {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(Integer.toString(version).getBytes(StandardCharsets.UTF_8));
      for (Step step : steps) {
        digest.update((byte) 0);
        digest.update(step.fingerprint().getBytes(StandardCharsets.UTF_8));
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JDK", e);
    }
  }

  public static Step sql(String statement) {
    return new Step(
        statement.strip(),
        connection -> {
          try (Statement st = connection.createStatement()) {
            st.execute(statement);
          }
        });
  }

  /** Runs {@code ALTER TABLE ... ADD COLUMN} only when the column does not exist yet. */
  public static Step addColumnIfMissing(String table, String column, String definition) {
    requireIdentifier(table);
    requireIdentifier(column);
    String alter = "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition;
    return new Step(
        "addColumnIfMissing:" + alter,
        connection -> {
          if (!hasColumn(connection, table, column)) {
            try (Statement st = connection.createStatement()) {
              st.execute(alter);
            }
          }
        });
  }

  static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
    requireIdentifier(table);
    try (Statement st = connection.createStatement();
        ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rs.next()) {
        if (column.equalsIgnoreCase(rs.getString("name"))) {
          return true;
        }
      }
      return false;
    }
  }

  private static void requireIdentifier(String name) {
    if (!IDENTIFIER.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "Not a plain SQL identifier: " + name.toLowerCase(Locale.ROOT));
    }
  }
}
