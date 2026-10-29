package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.memory.LongTermMemoryStore;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryStoreException;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

class MemoryServiceTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-03-04T00:00:00Z"), ZoneOffset.UTC);
  @TempDir Path temp;

  private LongTermMemoryStore markdown() {
    return new MarkdownMemoryStore(temp.resolve("md").resolve("MEMORY.md"), CLOCK);
  }

  private LongTermMemoryStore sqlite() {
    try {
      SQLiteDataSource ds = new SQLiteDataSource();
      ds.setUrl("jdbc:sqlite:" + temp.resolve("svc.db").toString().replace('\\', '/'));
      try (Connection c = ds.getConnection()) {
        ScriptUtils.executeSqlScript(c, new ClassPathResource("db/schema.sql"));
      }
      return new SqliteMemoryStore(ds, CLOCK);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The same scenario body runs over every backend: the service code never changes. */
  private void onEachBackend(java.util.function.Consumer<Supplier<LongTermMemoryStore>> scenario) {
    scenario.accept(this::markdown);
    scenario.accept(this::sqlite);
  }

  @Test
  void emptyMemoryAddsNothingToThePrompt() {
    onEachBackend(
        backend ->
            assertEquals(
                "",
                new MemoryService(backend.get(), MemoryService.Limits.DEFAULT).promptSection()));
  }

  @Test
  void aFactSavedNowIsInTheVeryNextPromptSection() {
    onEachBackend(
        backend -> {
          var service = new MemoryService(backend.get(), MemoryService.Limits.DEFAULT);
          service.save(MemoryScope.CORE, "name is Alex");
          assertTrue(service.promptSection().contains("name is Alex"));
          service.save(MemoryScope.ARCHIVAL, "met Sam");
          assertTrue(service.promptSection().contains("met Sam"));
        });
  }

  @Test
  void fiveHundredArchivalEntriesDoNotTruncateCoreAndArchivalIsCut() {
    onEachBackend(
        backend -> {
          var service = new MemoryService(backend.get(), new MemoryService.Limits(100, 300));
          service.save(MemoryScope.CORE, "CORE-ONE " + "c".repeat(500));
          service.save(MemoryScope.CORE, "CORE-TWO");
          for (int i = 0; i < 500; i++) {
            service.save(MemoryScope.ARCHIVAL, "archive-" + i);
          }
          String section = service.promptSection();
          assertTrue(section.contains("CORE-ONE " + "c".repeat(500)), "core is kept whole");
          assertTrue(section.contains("CORE-TWO"));
          assertTrue(section.contains("archive-499"), "newest archive entry is kept");
          assertFalse(
              section.contains("archive-0\n") || section.endsWith("archive-0"), "oldest is cut");
          long archiveLines = section.lines().filter(l -> l.startsWith("- archive-")).count();
          assertTrue(archiveLines > 0 && archiveLines < 100, "archive cut by the 300 char budget");
          assertTrue(
              section.lines().filter(l -> l.startsWith("- archive-")).mapToInt(String::length).sum()
                  <= 300 + archiveLines * 2);
        });
  }

  @Test
  void archivalEntriesKeepChronologicalOrderInThePrompt() {
    onEachBackend(
        backend -> {
          var service = new MemoryService(backend.get(), MemoryService.Limits.DEFAULT);
          service.save(MemoryScope.ARCHIVAL, "first");
          service.save(MemoryScope.ARCHIVAL, "second");
          String section = service.promptSection();
          assertTrue(section.indexOf("first") < section.indexOf("second"));
        });
  }

  @Test
  void anOversizedNewestEntryIsShortenedNotDropped() {
    onEachBackend(
        backend -> {
          var service = new MemoryService(backend.get(), new MemoryService.Limits(10, 20));
          service.save(MemoryScope.ARCHIVAL, "z".repeat(100));
          assertTrue(service.promptSection().contains("- " + "z".repeat(20)));
          assertFalse(service.promptSection().contains("z".repeat(21)));
        });
  }

  @Test
  void recallSeesArchivalOnly() {
    onEachBackend(
        backend -> {
          var service = new MemoryService(backend.get(), MemoryService.Limits.DEFAULT);
          service.save(MemoryScope.CORE, "tea is core");
          service.save(MemoryScope.ARCHIVAL, "tea at noon");
          assertEquals(
              List.of("tea at noon"),
              service.recall("TEA").stream().map(e -> e.content()).toList());
        });
  }

  @Test
  void invalidInputIsRejected() {
    var service = new MemoryService(markdown(), MemoryService.Limits.DEFAULT);
    assertThrows(IllegalArgumentException.class, () -> service.save(MemoryScope.CORE, " "));
    assertThrows(IllegalArgumentException.class, () -> service.recall(""));
    assertThrows(NullPointerException.class, () -> service.save(null, "x"));
    assertThrows(IllegalArgumentException.class, () -> new MemoryService.Limits(0, 10));
    assertThrows(IllegalArgumentException.class, () -> new MemoryService.Limits(10, 0));
  }

  @Test
  void backendFailuresPropagateAsMemoryStoreException() {
    LongTermMemoryStore broken =
        new LongTermMemoryStore() {
          public void save(MemoryScope s, String c) {
            throw new MemoryStoreException("disk full");
          }

          public List<io.oryxos.core.memory.MemoryEntry> readCore() {
            throw new MemoryStoreException("disk gone");
          }

          public List<io.oryxos.core.memory.MemoryEntry> readArchival(int limit) {
            throw new MemoryStoreException("disk gone");
          }

          public List<io.oryxos.core.memory.MemoryEntry> searchArchival(String k, int limit) {
            throw new MemoryStoreException("disk gone");
          }
        };
    var service = new MemoryService(broken, MemoryService.Limits.DEFAULT);
    assertThrows(MemoryStoreException.class, () -> service.save(MemoryScope.CORE, "x"));
    assertThrows(MemoryStoreException.class, service::promptSection);
    assertThrows(MemoryStoreException.class, () -> service.recall("x"));
  }
}
