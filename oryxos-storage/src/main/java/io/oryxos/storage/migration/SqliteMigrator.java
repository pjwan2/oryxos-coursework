package io.oryxos.storage.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

/**
 * Lightweight, versioned SQLite migration (no Flyway). Rules:
 *
 * <ul>
 *   <li>Applied versions are recorded in {@code schema_migrations} and must be exactly 1..n. A gap,
 *       a version this build does not know (database from a newer release) or a checksum that no
 *       longer matches stops startup.
 *   <li>Every migration runs in its own {@code BEGIN IMMEDIATE} transaction together with its
 *       version row, so a failure leaves the previous version intact.
 *   <li>A brand-new database receives the full {@code db/schema.sql} and is stamped with every
 *       version. An existing database is upgraded step by step; column additions are guarded by an
 *       existence check, so repeating a step is harmless.
 *   <li>Before upgrading an existing file the database is copied with {@code VACUUM INTO}, the
 *       upgrade is rehearsed on a copy of that backup, and only if the rehearsal succeeds and keeps
 *       every row of every old table is the real file touched. Backups are never deleted here.
 * </ul>
 *
 * Single-instance assumption: SQLite's write lock serialises concurrent starters, but this is not a
 * distributed migration lock.
 */
public final class SqliteMigrator {
  private static final Logger log = LoggerFactory.getLogger(SqliteMigrator.class);
  private static final String VERSION_TABLE = "schema_migrations";
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

  private final List<Migration> migrations;
  private final String freshSchemaResource;
  private final boolean backupBeforeMigrate;
  private final Clock clock;

  /**
   * @param freshSchemaResource classpath script for a brand-new database, or null to build a new
   *     database by applying every migration
   */
  public SqliteMigrator(
      List<Migration> migrations,
      String freshSchemaResource,
      boolean backupBeforeMigrate,
      Clock clock) {
    this.migrations = List.copyOf(migrations);
    for (int i = 0; i < this.migrations.size(); i++) {
      if (this.migrations.get(i).version() != i + 1) {
        throw new IllegalArgumentException("Migrations must be numbered 1..n without gaps");
      }
    }
    this.freshSchemaResource = freshSchemaResource;
    this.backupBeforeMigrate = backupBeforeMigrate;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public int latestVersion() {
    return migrations.size();
  }

  public MigrationReport migrate(DataSource dataSource) {
    Path file = databaseFile(dataSource);
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(true);
      return migrate(connection, file);
    } catch (SQLException e) {
      throw new MigrationException("Database migration failed: " + e.getMessage());
    }
  }

  private MigrationReport migrate(Connection connection, Path file) throws SQLException {
    Map<String, Long> before = rowCounts(connection);
    boolean tablesExist = !before.isEmpty();
    List<StoredVersion> stored = storedVersions(connection);
    boolean fresh = !tablesExist && stored.isEmpty() && !tableExists(connection, VERSION_TABLE);
    int current = validate(stored);

    if (fresh) {
      applyFresh(connection);
      log.info("Created a new database at schema version {}", latestVersion());
      return new MigrationReport(
          true, 0, latestVersion(), versionsAfter(0), null, before, rowCounts(connection));
    }
    if (current == latestVersion()) {
      return new MigrationReport(
          false, current, current, List.of(), null, before, rowCounts(connection));
    }

    Path backup = null;
    if (backupBeforeMigrate && file != null) {
      backup = backUp(connection, file, current);
      rehearse(backup, file, before);
    } else if (file == null) {
      log.warn("Database is not a file: skipping backup and rehearsal");
    }
    for (Migration migration : migrations.subList(current, migrations.size())) {
      apply(connection, migration);
    }
    Map<String, Long> after = rowCounts(connection);
    requireNoRowsLost(before, after, "Migration");
    log.info("Migrated database from version {} to {}", current, latestVersion());
    return new MigrationReport(
        false, current, latestVersion(), versionsAfter(current), backup, before, after);
  }

  private List<Integer> versionsAfter(int current) {
    List<Integer> versions = new ArrayList<>();
    for (int v = current + 1; v <= latestVersion(); v++) {
      versions.add(v);
    }
    return versions;
  }

  /** Verifies the recorded history is a known prefix of this build's migrations. */
  private int validate(List<StoredVersion> stored) {
    for (int i = 0; i < stored.size(); i++) {
      StoredVersion row = stored.get(i);
      if (row.version() != i + 1) {
        throw new MigrationException(
            "schema_migrations is not contiguous: expected version "
                + (i + 1)
                + " but found "
                + row.version());
      }
      if (row.version() > latestVersion()) {
        throw new MigrationException(
            "Database schema version "
                + row.version()
                + " is newer than this application supports ("
                + latestVersion()
                + "); refusing to start");
      }
      Migration known = migrations.get(i);
      if (!known.checksum().equals(row.checksum())) {
        throw new MigrationException(
            "Migration "
                + row.version()
                + " was changed after it was applied (checksum mismatch); refusing to start");
      }
    }
    return stored.size();
  }

  private void applyFresh(Connection connection) throws SQLException {
    inTransaction(
        connection,
        () -> {
          if (freshSchemaResource == null) {
            for (Migration migration : migrations) {
              runSteps(connection, migration);
            }
          } else {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(freshSchemaResource));
          }
          createVersionTable(connection);
          for (Migration migration : migrations) {
            record(connection, migration);
          }
        },
        "creating a new database");
  }

  private void apply(Connection connection, Migration migration) throws SQLException {
    inTransaction(
        connection,
        () -> {
          createVersionTable(connection);
          runSteps(connection, migration);
          record(connection, migration);
        },
        "migration " + migration.version() + " (" + migration.description() + ")");
  }

  private void runSteps(Connection connection, Migration migration) throws SQLException {
    for (Migration.Step step : migration.steps()) {
      step.action().run(connection);
    }
  }

  private void createVersionTable(Connection connection) throws SQLException {
    try (Statement st = connection.createStatement()) {
      st.execute(
          "CREATE TABLE IF NOT EXISTS "
              + VERSION_TABLE
              + " (version INTEGER PRIMARY KEY, description TEXT NOT NULL, "
              + "checksum TEXT NOT NULL, applied_at TEXT NOT NULL)");
    }
  }

  private void record(Connection connection, Migration migration) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO "
                + VERSION_TABLE
                + "(version, description, checksum, applied_at)"
                + " VALUES (?, ?, ?, ?)")) {
      ps.setInt(1, migration.version());
      ps.setString(2, migration.description());
      ps.setString(3, migration.checksum());
      ps.setString(4, clock.instant().toString());
      ps.executeUpdate();
    }
  }

  @FunctionalInterface
  private interface Work {
    void run() throws SQLException;
  }

  private void inTransaction(Connection connection, Work work, String what) throws SQLException {
    try (Statement st = connection.createStatement()) {
      st.execute("BEGIN IMMEDIATE");
      try {
        work.run();
        st.execute("COMMIT");
      } catch (SQLException | RuntimeException e) {
        rollbackQuietly(st);
        throw new MigrationException(
            "Database migration failed while " + what + ": " + e.getMessage());
      }
    }
  }

  private void rollbackQuietly(Statement st) {
    try {
      st.execute("ROLLBACK");
    } catch (SQLException e) {
      log.warn("Rollback failed: {}", e.getClass().getSimpleName());
    }
  }

  private Path backUp(Connection connection, Path file, int fromVersion) throws SQLException {
    String name =
        file.getFileName() + ".pre-v" + fromVersion + "-" + STAMP.format(clock.instant()) + ".bak";
    Path backup = file.resolveSibling(name);
    if (Files.exists(backup)) {
      throw new MigrationException("Backup file already exists: " + backup);
    }
    try (PreparedStatement ps = connection.prepareStatement("VACUUM INTO ?")) {
      ps.setString(1, backup.toString());
      ps.execute();
    }
    log.info("Backed up the database to {}", backup);
    return backup;
  }

  /** Runs the same upgrade on a copy of the backup; the real file is not touched. */
  private void rehearse(Path backup, Path file, Map<String, Long> expected) {
    Path copy =
        file.resolveSibling(file.getFileName() + ".rehearsal-" + System.nanoTime() + ".tmp");
    try {
      Files.copy(backup, copy);
      SQLiteDataSource rehearsal = new SQLiteDataSource();
      rehearsal.setUrl("jdbc:sqlite:" + copy);
      MigrationReport report =
          new SqliteMigrator(migrations, freshSchemaResource, false, clock).migrate(rehearsal);
      requireNoRowsLost(expected, report.rowCountsAfter(), "Rehearsal");
      log.info("Rehearsal on a copy succeeded (versions {})", report.applied());
    } catch (IOException e) {
      throw new MigrationException("Could not prepare the rehearsal copy: " + e.getMessage());
    } catch (MigrationException e) {
      throw new MigrationException(
          "Rehearsal on a copy failed, the original database was not changed (backup: "
              + backup
              + "): "
              + e.getMessage());
    } finally {
      deleteQuietly(copy);
      deleteQuietly(copy.resolveSibling(copy.getFileName() + "-journal"));
      deleteQuietly(copy.resolveSibling(copy.getFileName() + "-wal"));
      deleteQuietly(copy.resolveSibling(copy.getFileName() + "-shm"));
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      log.warn("Could not delete temporary file {}", path.getFileName());
    }
  }

  private static void requireNoRowsLost(
      Map<String, Long> before, Map<String, Long> after, String what) {
    for (Map.Entry<String, Long> table : before.entrySet()) {
      Long now = after.get(table.getKey());
      if (now == null || now < table.getValue()) {
        throw new MigrationException(
            what
                + " would lose rows in table "
                + table.getKey()
                + ": "
                + table.getValue()
                + " -> "
                + now);
      }
    }
  }

  private record StoredVersion(int version, String checksum) {}

  private List<StoredVersion> storedVersions(Connection connection) throws SQLException {
    if (!tableExists(connection, VERSION_TABLE)) {
      return List.of();
    }
    List<StoredVersion> rows = new ArrayList<>();
    try (Statement st = connection.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT version, checksum FROM " + VERSION_TABLE + " ORDER BY version")) {
      while (rs.next()) {
        rows.add(new StoredVersion(rs.getInt(1), rs.getString(2)));
      }
    }
    return rows;
  }

  private static boolean tableExists(Connection connection, String table) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
      ps.setString(1, table);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  /** Row counts of every application table (the version table is bookkeeping, not history). */
  static Map<String, Long> rowCounts(Connection connection) throws SQLException {
    List<String> tables = new ArrayList<>();
    try (Statement st = connection.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' "
                    + "AND name NOT LIKE 'sqlite_%' AND name <> '"
                    + VERSION_TABLE
                    + "' ORDER BY name")) {
      while (rs.next()) {
        tables.add(rs.getString(1));
      }
    }
    Map<String, Long> counts = new TreeMap<>();
    for (String table : tables) {
      try (Statement st = connection.createStatement();
          ResultSet rs =
              st.executeQuery("SELECT COUNT(*) FROM \"" + table.replace("\"", "\"\"") + "\"")) {
        long rows = 0L;
        if (rs.next()) {
          rows = rs.getLong(1);
        }
        counts.put(table, rows);
      }
    }
    return counts;
  }

  /** The database file behind the pool, or null for in-memory and unknown URLs. */
  static Path databaseFile(DataSource dataSource) {
    try (Connection connection = dataSource.getConnection()) {
      String url = connection.getMetaData().getURL();
      String prefix = "jdbc:sqlite:";
      if (url == null || !url.startsWith(prefix)) {
        return null;
      }
      String rest = url.substring(prefix.length());
      if (rest.contains(":memory:") || rest.contains("mode=memory")) {
        return null;
      }
      if (rest.startsWith("file:")) {
        rest = rest.substring("file:".length());
      }
      int query = rest.indexOf('?');
      if (query >= 0) {
        rest = rest.substring(0, query);
      }
      if (rest.isBlank()) {
        return null;
      }
      Path path = Path.of(rest);
      return Files.isRegularFile(path) ? path.toAbsolutePath() : null;
    } catch (SQLException | RuntimeException e) {
      log.warn("Could not determine the database file: {}", e.getClass().getSimpleName());
      return null;
    }
  }
}
