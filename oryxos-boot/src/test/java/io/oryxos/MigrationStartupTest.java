package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.storage.migration.MigrationException;
import io.oryxos.storage.migration.MigrationReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The schema is upgraded while the context is built; a failure means the application never serves.
 */
class MigrationStartupTest {
  @TempDir Path temp;

  private String url(Path db) {
    return "jdbc:sqlite:" + db.toString().replace('\\', '/');
  }

  private void sql(Path db, String... statements) throws SQLException {
    try (Connection c = DriverManager.getConnection(url(db));
        Statement st = c.createStatement()) {
      for (String statement : statements) {
        st.execute(statement);
      }
    }
  }

  private void legacyDatabase(Path db) throws SQLException {
    sql(
        db,
        "CREATE TABLE llm_calls (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL,"
            + " provider TEXT NOT NULL, model TEXT NOT NULL, prompt_tokens INTEGER NOT NULL DEFAULT 0,"
            + " completion_tokens INTEGER NOT NULL DEFAULT 0, duration_ms INTEGER NOT NULL DEFAULT 0,"
            + " success INTEGER NOT NULL, error_message TEXT, occurred_at TIMESTAMP NOT NULL)",
        "CREATE TABLE sessions (session_id TEXT PRIMARY KEY, profile_name TEXT NOT NULL,"
            + " channel TEXT NOT NULL, user_id TEXT NOT NULL, messages_json TEXT NOT NULL,"
            + " created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)",
        "CREATE TABLE tool_invocations (id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " session_id TEXT NOT NULL, tool_name TEXT NOT NULL, success INTEGER NOT NULL,"
            + " error_category TEXT, duration_ms INTEGER NOT NULL DEFAULT 0,"
            + " occurred_at TIMESTAMP NOT NULL)",
        "INSERT INTO sessions VALUES ('web:u:payment-demo','payment-demo','web','u','[]',"
            + "'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')",
        "INSERT INTO tool_invocations(session_id, tool_name, success, error_category, occurred_at)"
            + " VALUES ('web:u:payment-demo','get_payment',1,NULL,'2026-01-01T00:00:00Z'),"
            + "('web:u:payment-demo','get_payment',0,'ToolReturnedFailure','2026-01-01T00:00:01Z')");
  }

  private ConfigurableApplicationContext start(Path db, WebApplicationType type)
      throws IOException {
    Path profiles = Files.createDirectories(temp.resolve("profiles"));
    return new SpringApplicationBuilder(OryxOsApplication.class)
        .web(type)
        .bannerMode(Banner.Mode.OFF)
        .run(
            "--logging.level.root=WARN",
            "--server.port=0",
            "--oryxos.profile-directory=" + profiles.toString().replace('\\', '/'),
            "--spring.datasource.url=" + url(db),
            "--oryxos.memory.markdown-path=" + temp.resolve("M.md").toString().replace('\\', '/'));
  }

  private List<String> filesNamed(String prefix) throws IOException {
    try (Stream<Path> files = Files.list(temp)) {
      return files
          .map(p -> p.getFileName().toString())
          .filter(n -> n.startsWith(prefix))
          .sorted()
          .toList();
    }
  }

  @Test
  void anOldDatabaseIsUpgradedBeforeTheApplicationServesAndNoRowIsLost() throws Exception {
    Path db = temp.resolve("old.db");
    legacyDatabase(db);

    try (ConfigurableApplicationContext context = start(db, WebApplicationType.NONE)) {
      MigrationReport report = context.getBean(MigrationReport.class);
      assertEquals(List.of(1, 2, 3, 4), report.applied());
      assertEquals(2L, report.rowCountsBefore().get("tool_invocations"));
      assertEquals(2L, report.rowCountsAfter().get("tool_invocations"));

      JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
      assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM tool_invocations", Integer.class));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM sessions WHERE session_id = 'web:u:payment-demo'",
              Integer.class));
      assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_tasks", Integer.class));
      assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM schema_migrations", Integer.class));
    }
    assertEquals(1, filesNamed("old.db.pre-v0-").size(), "one backup of the old file");
  }

  @Test
  void aSecondStartDoesNotMigrateOrBackUpAgain() throws Exception {
    Path db = temp.resolve("twice.db");
    legacyDatabase(db);
    try (ConfigurableApplicationContext first = start(db, WebApplicationType.NONE)) {
      assertEquals(List.of(1, 2, 3, 4), first.getBean(MigrationReport.class).applied());
    }
    try (ConfigurableApplicationContext second = start(db, WebApplicationType.NONE)) {
      assertEquals(List.of(), second.getBean(MigrationReport.class).applied());
      assertEquals(4, second.getBean(MigrationReport.class).toVersion());
    }
    assertEquals(1, filesNamed("twice.db.pre-v").size());
  }

  @Test
  void aBrandNewDatabaseNeedsNoBackup() throws Exception {
    Path db = temp.resolve("new.db");
    try (ConfigurableApplicationContext context = start(db, WebApplicationType.NONE)) {
      MigrationReport report = context.getBean(MigrationReport.class);
      assertTrue(report.freshDatabase());
      assertEquals(4, report.toVersion());
    }
    assertEquals(List.of(), filesNamed("new.db.pre-v"));
  }

  @Test
  void aDatabaseFromANewerReleaseStopsTheApplicationBeforeItServes() throws Exception {
    Path db = temp.resolve("future.db");
    try (ConfigurableApplicationContext context = start(db, WebApplicationType.NONE)) {
      assertTrue(context.isActive());
    }
    sql(db, "INSERT INTO schema_migrations VALUES (5, 'from the future', 'x', '2030-01-01')");

    // A servlet application: if the migration failure did not abort the context, a web server
    // would be listening. run() throwing proves it was never started.
    Exception failure =
        assertThrows(Exception.class, () -> start(db, WebApplicationType.SERVLET).close());
    Throwable root = NestedExceptionUtils.getRootCause(failure);
    assertInstanceOf(MigrationException.class, root == null ? failure : root);
    assertTrue(root.getMessage().contains("newer than this application"), root.getMessage());
  }

  @Test
  void aTamperedMigrationHistoryStopsTheApplication() throws Exception {
    Path db = temp.resolve("tampered.db");
    try (ConfigurableApplicationContext context = start(db, WebApplicationType.NONE)) {
      assertTrue(context.isActive());
    }
    sql(db, "UPDATE schema_migrations SET checksum = 'changed' WHERE version = 2");

    Exception failure =
        assertThrows(Exception.class, () -> start(db, WebApplicationType.NONE).close());
    Throwable root = NestedExceptionUtils.getRootCause(failure);
    assertInstanceOf(MigrationException.class, root);
    assertTrue(root.getMessage().contains("checksum"), root.getMessage());
  }

  @Test
  void aBrokenLegacyDatabaseIsLeftUntouchedWhenTheUpgradeCannotWork() throws Exception {
    Path db = temp.resolve("broken.db");
    legacyDatabase(db);
    // A table with the name of a new one but an incompatible shape: CREATE ... IF NOT EXISTS keeps
    // it, and the later index creation on a missing column fails during the rehearsal.
    sql(db, "CREATE TABLE task_executions (id INTEGER PRIMARY KEY, unrelated TEXT)");

    Exception failure =
        assertThrows(Exception.class, () -> start(db, WebApplicationType.NONE).close());
    Throwable root = NestedExceptionUtils.getRootCause(failure);
    assertInstanceOf(MigrationException.class, root);
    assertTrue(root.getMessage().contains("Rehearsal on a copy failed"), root.getMessage());

    try (Connection c = DriverManager.getConnection(url(db));
        Statement st = c.createStatement();
        var rs =
            st.executeQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE name = 'schema_migrations'")) {
      rs.next();
      assertEquals(0, rs.getInt(1), "the real file was never migrated");
    }
    try (Connection c = DriverManager.getConnection(url(db));
        Statement st = c.createStatement();
        var rs = st.executeQuery("SELECT COUNT(*) FROM tool_invocations")) {
      rs.next();
      assertEquals(2, rs.getInt(1));
    }
    assertEquals(1, filesNamed("broken.db.pre-v0-").size(), "the backup is kept for inspection");
  }
}
