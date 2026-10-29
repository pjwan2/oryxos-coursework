package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.oryxos.core.memory.LongTermMemoryStore;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryStoreException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

/** Runs the project's real db/schema.sql (the same file Spring applies at startup). */
class SqliteMemoryStoreTest extends MemoryStoreContractTest {
  @TempDir Path temp;
  private SQLiteDataSource dataSource;

  private SQLiteDataSource dataSource(boolean withSchema) throws SQLException {
    SQLiteDataSource ds = new SQLiteDataSource();
    ds.setUrl("jdbc:sqlite:" + temp.resolve("memory.db").toString().replace('\\', '/'));
    if (withSchema) {
      try (Connection c = ds.getConnection()) {
        ScriptUtils.executeSqlScript(c, new ClassPathResource("db/schema.sql"));
      }
    }
    return ds;
  }

  @Override
  protected LongTermMemoryStore create() throws Exception {
    dataSource = dataSource(true);
    return new SqliteMemoryStore(dataSource, FIXED);
  }

  @Override
  protected LongTermMemoryStore reopen() throws Exception {
    SQLiteDataSource second = new SQLiteDataSource();
    second.setUrl(dataSource.getUrl());
    return new SqliteMemoryStore(second, FIXED);
  }

  @Test
  void schemaRejectsAnUnknownScopeEvenFromRawSql() throws Exception {
    create();
    try (Connection c = dataSource.getConnection();
        Statement st = c.createStatement()) {
      assertThrows(
          SQLException.class,
          () ->
              st.executeUpdate(
                  "INSERT INTO memory_entries (scope, content, saved_on)"
                      + " VALUES ('BOGUS', 'x', '2026-01-01')"));
    }
  }

  @Test
  void rowsHaveTheExpectedColumns() throws Exception {
    LongTermMemoryStore store = create();
    store.save(MemoryScope.CORE, "c");
    store.save(MemoryScope.ARCHIVAL, "a");
    try (Connection c = dataSource.getConnection();
        Statement st = c.createStatement();
        var rs =
            st.executeQuery("SELECT scope, content, saved_on FROM memory_entries ORDER BY id")) {
      rs.next();
      assertEquals(
          "CORE|c|2026-03-04", rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3));
      rs.next();
      assertEquals("ARCHIVAL", rs.getString(1));
    }
  }

  @Test
  void missingTableIsReportedAsMemoryStoreExceptionNotASilentEmptyResult() throws Exception {
    SqliteMemoryStore store = new SqliteMemoryStore(dataSource(false), FIXED);
    assertThrows(MemoryStoreException.class, store::readCore);
    assertThrows(MemoryStoreException.class, () -> store.save(MemoryScope.CORE, "x"));
    assertThrows(MemoryStoreException.class, () -> store.searchArchival("x", 5));
  }
}
