package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.oryxos.memory.SqliteMemoryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Backend 2: SQLite. Same assertions as the Markdown test, only the property differs. */
@SpringBootTest(properties = "oryxos.profile-directory=../.oryxos/profiles")
class SqliteMemoryPromptIntegrationTest extends AbstractMemoryPromptIntegrationTest {
  private static final Path DIR = workDir();
  @Autowired DataSource dataSource;

  @DynamicPropertySource
  static void backend(DynamicPropertyRegistry registry) {
    registry.add("oryxos.memory.backend", () -> "sqlite");
    registry.add("oryxos.memory.markdown-path", () -> DIR.resolve("MEMORY.md").toString());
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + DIR.resolve("app.db").toString().replace('\\', '/'));
  }

  @Test
  void theConfiguredBackendIsSqliteAndRowsLandInMemoryEntries() throws Exception {
    assertInstanceOf(SqliteMemoryStore.class, store);
    agent.process(
        "web",
        "sq-user",
        "memory-demo",
        "tool:save_memory {\"content\":\"sq core\",\"scope\":\"CORE\"}");
    agent.process("web", "sq-user", "memory-demo", "tool:save_memory {\"content\":\"sq archive\"}");
    try (Connection c = dataSource.getConnection();
        ResultSet rs =
            c.createStatement()
                .executeQuery(
                    "SELECT scope FROM memory_entries WHERE content IN ('sq core','sq archive') ORDER BY id")) {
      rs.next();
      assertEquals("CORE", rs.getString(1));
      rs.next();
      assertEquals("ARCHIVAL", rs.getString(1));
    }
    assertFalse(
        Files.exists(DIR.resolve("MEMORY.md")), "the markdown file is not used in sqlite mode");
  }
}
