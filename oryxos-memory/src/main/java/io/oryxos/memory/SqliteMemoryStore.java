package io.oryxos.memory;

import io.oryxos.core.memory.LongTermMemoryStore;
import io.oryxos.core.memory.MemoryEntry;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryStoreException;
import io.oryxos.core.memory.MemoryText;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Backed by the {@code memory_entries} table from db/schema.sql (the project's migration file; this
 * class never creates tables). Every call is its own short statement, so there is no cache and a
 * write is visible to the next read, also from a new process.
 */
public final class SqliteMemoryStore implements LongTermMemoryStore {
  private final DataSource dataSource;
  private final Clock clock;

  public SqliteMemoryStore(DataSource dataSource, Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void save(MemoryScope scope, String content) {
    Objects.requireNonNull(scope, "scope");
    String text = MemoryText.normalize(content);
    String sql = "INSERT INTO memory_entries (scope, content, saved_on) VALUES (?, ?, ?)";
    try (Connection c = dataSource.getConnection();
        PreparedStatement st = c.prepareStatement(sql)) {
      st.setString(1, scope.name());
      st.setString(2, text);
      st.setString(3, LocalDate.now(clock).toString());
      st.executeUpdate();
    } catch (SQLException e) {
      throw new MemoryStoreException("Unable to save memory", e);
    }
  }

  @Override
  public List<MemoryEntry> readCore() {
    return query(
        "SELECT content, saved_on FROM memory_entries WHERE scope = 'CORE' ORDER BY id",
        MemoryScope.CORE,
        null,
        -1);
  }

  @Override
  public List<MemoryEntry> readArchival(int limit) {
    requirePositive(limit);
    return query(
        "SELECT content, saved_on FROM (SELECT id, content, saved_on FROM memory_entries"
            + " WHERE scope = 'ARCHIVAL' ORDER BY id DESC LIMIT ?) ORDER BY id",
        MemoryScope.ARCHIVAL,
        null,
        limit);
  }

  @Override
  public List<MemoryEntry> searchArchival(String keyword, int limit) {
    String needle = MemoryText.keyword(keyword);
    requirePositive(limit);
    // LIKE is case-insensitive for ASCII in SQLite; %, _ and \ in the keyword are escaped.
    String pattern =
        "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    return query(
        "SELECT content, saved_on FROM (SELECT id, content, saved_on FROM memory_entries"
            + " WHERE scope = 'ARCHIVAL' AND content LIKE ? ESCAPE '\\'"
            + " ORDER BY id DESC LIMIT ?) ORDER BY id",
        MemoryScope.ARCHIVAL,
        pattern,
        limit);
  }

  private List<MemoryEntry> query(String sql, MemoryScope scope, String pattern, int limit) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement st = c.prepareStatement(sql)) {
      int index = 1;
      if (pattern != null) {
        st.setString(index++, pattern);
      }
      if (limit > 0) {
        st.setInt(index, limit);
      }
      List<MemoryEntry> result = new ArrayList<>();
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          result.add(new MemoryEntry(scope, rs.getString(1), LocalDate.parse(rs.getString(2))));
        }
      }
      return List.copyOf(result);
    } catch (SQLException | RuntimeException e) {
      throw new MemoryStoreException("Unable to read memory", e);
    }
  }

  private static void requirePositive(int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive");
    }
  }
}
