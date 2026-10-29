package io.oryxos.storage.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Fresh creation, legacy upgrade, repeat starts, failures, backups and history preservation. */
class SqliteMigrationTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-03-01T10:20:30Z"), ZoneOffset.UTC);

  /** The schema exactly as it was before this phase: no error_message, no scheduler tables. */
  private static final String LEGACY_SCHEMA =
      """
      CREATE TABLE llm_calls (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL,
        provider TEXT NOT NULL, model TEXT NOT NULL, prompt_tokens INTEGER NOT NULL DEFAULT 0,
        completion_tokens INTEGER NOT NULL DEFAULT 0, duration_ms INTEGER NOT NULL DEFAULT 0,
        success INTEGER NOT NULL, error_message TEXT, occurred_at TIMESTAMP NOT NULL);
      CREATE INDEX idx_llm_calls_session ON llm_calls(session_id);
      CREATE TABLE sessions (session_id TEXT PRIMARY KEY, profile_name TEXT NOT NULL,
        channel TEXT NOT NULL, user_id TEXT NOT NULL, messages_json TEXT NOT NULL,
        created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL);
      CREATE TABLE tool_invocations (id INTEGER PRIMARY KEY AUTOINCREMENT,
        session_id TEXT NOT NULL, tool_name TEXT NOT NULL, success INTEGER NOT NULL,
        error_category TEXT, duration_ms INTEGER NOT NULL DEFAULT 0,
        occurred_at TIMESTAMP NOT NULL);
      CREATE INDEX idx_tool_session ON tool_invocations(session_id);
      CREATE TABLE memory_entries (id INTEGER PRIMARY KEY AUTOINCREMENT,
        scope TEXT NOT NULL CHECK (scope IN ('CORE', 'ARCHIVAL')), content TEXT NOT NULL,
        saved_on TEXT NOT NULL);
      CREATE INDEX idx_memory_scope ON memory_entries(scope, id);
      """;

  @TempDir Path dir;

  private static SQLiteDataSource source(Path file) {
    SQLiteDataSource ds = new SQLiteDataSource();
    ds.setUrl("jdbc:sqlite:" + file.toString().replace('\\', '/'));
    return ds;
  }

  private static SqliteMigrator migrator(boolean backup) {
    return new SqliteMigrator(Migrations.ALL, Migrations.FRESH_SCHEMA_RESOURCE, backup, CLOCK);
  }

  private static void exec(SQLiteDataSource ds, String... statements) throws SQLException {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      for (String sql : statements) {
        st.execute(sql);
      }
    }
  }

  private static long count(SQLiteDataSource ds, String sql) throws SQLException {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getLong(1) : -1;
    }
  }

  /** A legacy database with history in every table. */
  private SQLiteDataSource legacyDatabase(String name) throws SQLException {
    SQLiteDataSource ds = source(dir.resolve(name));
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      for (String statement : LEGACY_SCHEMA.split(";")) {
        if (!statement.isBlank()) {
          st.execute(statement);
        }
      }
      st.execute(
          "INSERT INTO llm_calls(session_id, provider, model, success, occurred_at) VALUES"
              + " ('s1','mock','m',1,'2026-01-01T00:00:00Z'),('s1','mock','m',0,'2026-01-01T00:00:01Z')");
      st.execute(
          "INSERT INTO sessions VALUES ('web:u:p','p','web','u','[]','2026-01-01','2026-01-02')");
      st.execute(
          "INSERT INTO tool_invocations(session_id, tool_name, success, error_category, occurred_at)"
              + " VALUES ('s1','file_read',1,NULL,'2026-01-01'),"
              + "('s1','file_read',0,'SandboxDenied','2026-01-02'),"
              + "('s1','shell',0,'ToolReturnedFailure','2026-01-03')");
      st.execute(
          "INSERT INTO memory_entries(scope, content, saved_on) VALUES"
              + " ('CORE','likes tea','2026-01-01'),('ARCHIVAL','met Bob','2026-01-02')");
    }
    return ds;
  }

  private static List<Integer> recordedVersions(SQLiteDataSource ds) throws SQLException {
    List<Integer> versions = new ArrayList<>();
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT version FROM schema_migrations ORDER BY version")) {
      while (rs.next()) {
        versions.add(rs.getInt(1));
      }
    }
    return versions;
  }

  /** table -> "name:type:notnull:default:pk" per column, plus index names. */
  private static Map<String, List<String>> shape(SQLiteDataSource ds) throws SQLException {
    Map<String, List<String>> result = new TreeMap<>();
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      List<String> tables = new ArrayList<>();
      try (ResultSet rs =
          st.executeQuery(
              "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
                  + " AND name <> 'schema_migrations' ORDER BY name")) {
        while (rs.next()) {
          tables.add(rs.getString(1));
        }
      }
      for (String table : tables) {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
          while (rs.next()) {
            columns.add(
                rs.getString("name")
                    + ":"
                    + rs.getString("type")
                    + ":"
                    + rs.getInt("notnull")
                    + ":"
                    + rs.getString("dflt_value")
                    + ":"
                    + rs.getInt("pk"));
          }
        }
        List<String> sorted = new ArrayList<>(columns);
        sorted.sort(String::compareTo);
        result.put(table, sorted);
      }
      List<String> indexes = new ArrayList<>();
      try (ResultSet rs =
          st.executeQuery(
              "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'"
                  + " ORDER BY name")) {
        while (rs.next()) {
          indexes.add(rs.getString(1));
        }
      }
      result.put("#indexes", indexes);
    }
    return result;
  }

  private List<String> filesInDir() throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      return files.map(p -> p.getFileName().toString()).sorted().toList();
    }
  }

  // ---- fresh database -----------------------------------------------------------------

  @Test
  void aBrandNewDatabaseGetsTheFullSchemaAndEveryVersionIsRecorded() throws Exception {
    SQLiteDataSource ds = source(dir.resolve("new.db"));
    MigrationReport report = migrator(true).migrate(ds);

    assertTrue(report.freshDatabase());
    assertEquals(3, report.toVersion());
    assertNull(report.backupFile(), "there is nothing to back up");
    assertEquals(List.of(1, 2, 3), recordedVersions(ds));
    assertEquals(0, count(ds, "SELECT COUNT(*) FROM scheduled_tasks"));
    assertEquals(0, count(ds, "SELECT COUNT(*) FROM task_executions"));
    assertEquals(
        1,
        count(
            ds,
            "SELECT COUNT(*) FROM pragma_table_info('tool_invocations') WHERE name='error_message'"));
    assertEquals(
        List.of("new.db"),
        filesInDir().stream().filter(n -> !n.endsWith("-journal")).toList(),
        "no backup, rehearsal or stray file for a new database");
  }

  @Test
  void startingAgainChangesNothingAndMakesNoBackup() throws Exception {
    SQLiteDataSource ds = source(dir.resolve("again.db"));
    migrator(true).migrate(ds);
    String stamp = stringOf(ds, "SELECT applied_at FROM schema_migrations WHERE version = 3");

    MigrationReport second = migrator(true).migrate(ds);
    MigrationReport third = migrator(true).migrate(ds);

    assertFalse(second.freshDatabase());
    assertEquals(List.of(), second.applied());
    assertEquals(List.of(), third.applied());
    assertEquals(3, second.fromVersion());
    assertNull(second.backupFile());
    assertEquals(stamp, stringOf(ds, "SELECT applied_at FROM schema_migrations WHERE version = 3"));
    assertEquals(List.of(1, 2, 3), recordedVersions(ds));
    assertEquals(List.of("again.db"), filesInDir());
  }

  private static String stringOf(SQLiteDataSource ds, String sql) throws SQLException {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  // ---- legacy upgrade -----------------------------------------------------------------

  @Test
  void aLegacyDatabaseIsUpgradedInPlaceWithoutLosingAnyRow() throws Exception {
    SQLiteDataSource ds = legacyDatabase("legacy.db");

    MigrationReport report = migrator(true).migrate(ds);

    assertFalse(report.freshDatabase());
    assertEquals(0, report.fromVersion());
    assertEquals(List.of(1, 2, 3), report.applied());
    assertEquals(List.of(1, 2, 3), recordedVersions(ds));
    assertEquals(2L, report.rowCountsBefore().get("llm_calls"));
    assertEquals(1L, report.rowCountsBefore().get("sessions"));
    assertEquals(3L, report.rowCountsBefore().get("tool_invocations"));
    assertEquals(2L, report.rowCountsBefore().get("memory_entries"));
    for (var table : report.rowCountsBefore().entrySet()) {
      assertEquals(table.getValue(), report.rowCountsAfter().get(table.getKey()), table.getKey());
    }
    assertEquals(
        "SandboxDenied", stringOf(ds, "SELECT error_category FROM tool_invocations WHERE id = 2"));
    assertNull(
        stringOf(ds, "SELECT error_message FROM tool_invocations WHERE id = 2"),
        "history keeps a NULL reason; nothing is invented");
    assertEquals("likes tea", stringOf(ds, "SELECT content FROM memory_entries WHERE id = 1"));
    assertEquals(0, count(ds, "SELECT COUNT(*) FROM scheduled_tasks"));
  }

  @Test
  void anExistingDatabaseIsBackedUpBeforeItIsTouched() throws Exception {
    SQLiteDataSource ds = legacyDatabase("legacy.db");
    MigrationReport report = migrator(true).migrate(ds);

    Path backup = report.backupFile();
    assertTrue(Files.isRegularFile(backup), "backup must exist and stay on disk");
    assertTrue(backup.getFileName().toString().startsWith("legacy.db.pre-v0-20260301102030"));
    SQLiteDataSource copy = source(backup);
    assertEquals(3, count(copy, "SELECT COUNT(*) FROM tool_invocations"));
    assertEquals(
        0,
        count(
            copy,
            "SELECT COUNT(*) FROM pragma_table_info('tool_invocations') WHERE name='error_message'"),
        "the backup is the database as it was before the upgrade");
    assertEquals(
        0, count(copy, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'scheduled_tasks'"));
  }

  @Test
  void theRehearsalLeavesNoTemporaryFileBehind() throws Exception {
    SQLiteDataSource ds = legacyDatabase("legacy.db");
    migrator(true).migrate(ds);
    assertTrue(
        filesInDir().stream().noneMatch(n -> n.contains("rehearsal")), filesInDir().toString());
    assertEquals(
        2, filesInDir().stream().filter(n -> n.startsWith("legacy.db")).count(), "db + backup");
  }

  @Test
  void backupCanBeSwitchedOff() throws Exception {
    SQLiteDataSource ds = legacyDatabase("legacy.db");
    MigrationReport report = migrator(false).migrate(ds);
    assertNull(report.backupFile());
    assertEquals(List.of("legacy.db"), filesInDir());
    assertEquals(List.of(1, 2, 3), recordedVersions(ds));
  }

  @Test
  void aColumnThatIsAlreadyThereIsNotAddedTwice() throws Exception {
    SQLiteDataSource ds = legacyDatabase("half.db");
    exec(ds, "ALTER TABLE tool_invocations ADD COLUMN error_message TEXT");
    exec(ds, "UPDATE tool_invocations SET error_message = 'kept' WHERE id = 1");

    MigrationReport report = migrator(true).migrate(ds);

    assertEquals(List.of(1, 2, 3), report.applied());
    assertEquals("kept", stringOf(ds, "SELECT error_message FROM tool_invocations WHERE id = 1"));
  }

  @Test
  void aDatabaseFromBeforeTheMemoryTableGetsItToo() throws Exception {
    SQLiteDataSource ds = legacyDatabase("old.db");
    exec(ds, "DROP TABLE memory_entries");
    migrator(true).migrate(ds);
    assertEquals(0, count(ds, "SELECT COUNT(*) FROM memory_entries"));
    assertEquals(
        4L,
        count(
            ds,
            "SELECT COUNT(*) FROM sqlite_master WHERE name = 'scheduled_tasks' OR name = 'task_executions' OR name = 'memory_entries' OR name = 'schema_migrations'"));
  }

  @Test
  void aNewDatabaseAndAnUpgradedOneEndUpWithTheSameSchema() throws Exception {
    SQLiteDataSource fresh = source(dir.resolve("fresh.db"));
    migrator(true).migrate(fresh);
    SQLiteDataSource upgraded = legacyDatabase("upgraded.db");
    migrator(true).migrate(upgraded);

    assertEquals(shape(fresh), shape(upgraded));
    // And the stamped history is identical too, checksums included.
    assertEquals(
        stringOf(fresh, "SELECT group_concat(version || checksum) FROM schema_migrations"),
        stringOf(upgraded, "SELECT group_concat(version || checksum) FROM schema_migrations"));
  }

  @Test
  void aNewDatabaseBuiltOnlyFromTheMigrationsMatchesTheSchemaFile() throws Exception {
    SQLiteDataSource viaFile = source(dir.resolve("file.db"));
    migrator(true).migrate(viaFile);
    SQLiteDataSource viaSteps = source(dir.resolve("steps.db"));
    new SqliteMigrator(Migrations.ALL, null, true, CLOCK).migrate(viaSteps);
    assertEquals(shape(viaFile), shape(viaSteps), "db/schema.sql and Migrations drifted apart");
  }

  // ---- failure ------------------------------------------------------------------------

  private static List<Migration> withBrokenFourthMigration() {
    List<Migration> all = new ArrayList<>(Migrations.ALL);
    all.add(
        new Migration(
            4,
            "broken",
            List.of(
                Migration.sql("CREATE TABLE t4 (id INTEGER)"),
                Migration.sql("INSERT INTO table_that_does_not_exist VALUES (1)"))));
    return all;
  }

  @Test
  void aFailingMigrationIsRehearsedFirstSoTheRealDatabaseIsNeverTouched() throws Exception {
    SQLiteDataSource ds = legacyDatabase("rehearsed.db");
    migrator(true).migrate(ds); // now at version 3 with a backup
    long toolRows = count(ds, "SELECT COUNT(*) FROM tool_invocations");

    MigrationException error =
        assertThrows(
            MigrationException.class,
            () ->
                new SqliteMigrator(withBrokenFourthMigration(), null, true, laterClock())
                    .migrate(ds));

    assertTrue(error.getMessage().contains("Rehearsal on a copy failed"), error.getMessage());
    assertTrue(error.getMessage().contains("original database was not changed"));
    assertEquals(List.of(1, 2, 3), recordedVersions(ds));
    assertEquals(0, count(ds, "SELECT COUNT(*) FROM sqlite_master WHERE name = 't4'"));
    assertEquals(toolRows, count(ds, "SELECT COUNT(*) FROM tool_invocations"));
    assertTrue(filesInDir().stream().noneMatch(n -> n.contains("rehearsal")));
    assertTrue(
        filesInDir().stream().anyMatch(n -> n.contains(".pre-v3-")),
        "the backup taken for the failed attempt is kept");
  }

  private static Clock laterClock() {
    return Clock.fixed(Instant.parse("2026-03-02T00:00:00Z"), ZoneOffset.UTC);
  }

  @Test
  void aFailureInsideTheRealMigrationRollsTheWholeStepBack() throws Exception {
    SQLiteDataSource ds = legacyDatabase("rollback.db");
    migrator(false).migrate(ds);

    // Backup and rehearsal off: the failure happens on the real file, inside its transaction.
    MigrationException error =
        assertThrows(
            MigrationException.class,
            () -> new SqliteMigrator(withBrokenFourthMigration(), null, false, CLOCK).migrate(ds));

    assertTrue(error.getMessage().contains("migration 4"), error.getMessage());
    assertEquals(List.of(1, 2, 3), recordedVersions(ds), "version 4 was not recorded");
    assertEquals(
        0, count(ds, "SELECT COUNT(*) FROM sqlite_master WHERE name = 't4'"), "CREATE rolled back");
    assertEquals(3, count(ds, "SELECT COUNT(*) FROM tool_invocations"));
  }

  @Test
  void aBrokenFirstUpgradeOfALegacyDatabaseLeavesItAsItWas() throws Exception {
    SQLiteDataSource ds = legacyDatabase("legacy-broken.db");
    List<Migration> broken = new ArrayList<>();
    broken.add(Migrations.ALL.get(0));
    broken.add(
        new Migration(
            2,
            "broken second step",
            List.of(
                Migration.addColumnIfMissing("tool_invocations", "error_message", "TEXT"),
                Migration.sql("THIS IS NOT SQL"))));

    assertThrows(
        MigrationException.class, () -> new SqliteMigrator(broken, null, true, CLOCK).migrate(ds));

    assertEquals(
        0,
        count(
            ds,
            "SELECT COUNT(*) FROM pragma_table_info('tool_invocations') WHERE name='error_message'"),
        "the ALTER inside the failed migration was rolled back");
    assertEquals(3, count(ds, "SELECT COUNT(*) FROM tool_invocations"));
    assertEquals(2, count(ds, "SELECT COUNT(*) FROM memory_entries"));
  }

  @Test
  void aMigrationEditedAfterItWasAppliedStopsStartup() throws Exception {
    SQLiteDataSource ds = source(dir.resolve("edited.db"));
    migrator(true).migrate(ds);

    List<Migration> edited = new ArrayList<>(Migrations.ALL);
    edited.set(
        1,
        new Migration(
            2,
            "tool_invocations.error_message",
            List.of(
                Migration.addColumnIfMissing(
                    "tool_invocations", "error_message", "TEXT NOT NULL DEFAULT ''"))));

    MigrationException error =
        assertThrows(
            MigrationException.class,
            () -> new SqliteMigrator(edited, null, true, CLOCK).migrate(ds));
    assertTrue(error.getMessage().contains("checksum"), error.getMessage());
  }

  @Test
  void aGapInTheRecordedVersionsStopsStartup() throws Exception {
    SQLiteDataSource ds = source(dir.resolve("gap.db"));
    migrator(true).migrate(ds);
    exec(ds, "DELETE FROM schema_migrations WHERE version = 2");

    MigrationException error =
        assertThrows(MigrationException.class, () -> migrator(true).migrate(ds));
    assertTrue(error.getMessage().contains("not contiguous"), error.getMessage());
  }

  @Test
  void aDatabaseFromANewerReleaseIsRefused() throws Exception {
    SQLiteDataSource ds = source(dir.resolve("future.db"));
    migrator(true).migrate(ds);
    exec(ds, "INSERT INTO schema_migrations VALUES (4, 'from the future', 'abc', '2030-01-01')");

    MigrationException error =
        assertThrows(MigrationException.class, () -> migrator(true).migrate(ds));
    assertTrue(error.getMessage().contains("newer than this application"), error.getMessage());
  }

  @Test
  void migrationsMustBeNumberedFromOneWithoutGapsOrReordering() {
    Migration second = Migrations.ALL.get(1);
    Migration first = Migrations.ALL.get(0);
    assertThrows(
        IllegalArgumentException.class,
        () -> new SqliteMigrator(List.of(second), null, true, CLOCK));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SqliteMigrator(List.of(second, first), null, true, CLOCK));
    assertThrows(IllegalArgumentException.class, () -> new Migration(0, "zero", List.of()));
  }

  @Test
  void checksumsDifferPerMigrationAndAreStable() {
    assertEquals(Migrations.ALL.get(1).checksum(), Migrations.ALL.get(1).checksum());
    assertNotEquals(Migrations.ALL.get(0).checksum(), Migrations.ALL.get(1).checksum());
    assertNotEquals(Migrations.ALL.get(1).checksum(), Migrations.ALL.get(2).checksum());
  }

  @Test
  void identifiersInGuardedStepsAreChecked() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Migration.addColumnIfMissing("tool_invocations; DROP TABLE x", "c", "TEXT"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Migration.addColumnIfMissing("tool_invocations", "c d", "TEXT"));
  }

  @Test
  void anInMemoryDatabaseIsMigratedWithoutBackupOrRehearsal() throws Exception {
    SQLiteDataSource ds = new SQLiteDataSource();
    ds.setUrl("jdbc:sqlite:file:migration-test-mem?mode=memory&cache=shared");
    try (Connection keepAlive = ds.getConnection()) {
      MigrationReport report = migrator(true).migrate(ds);
      assertTrue(report.freshDatabase());
      assertNull(report.backupFile());
      assertEquals(List.of(1, 2, 3), recordedVersions(ds));
      assertTrue(keepAlive.isValid(1));
    }
  }
}
