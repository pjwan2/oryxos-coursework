package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.memory.LongTermMemoryStore;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryStoreException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownMemoryStoreTest extends MemoryStoreContractTest {
  @TempDir Path temp;

  /** A directory that does not exist yet, as on a fresh install. */
  private Path file() {
    return temp.resolve(".oryxos").resolve("memory").resolve("MEMORY.md");
  }

  @Override
  protected LongTermMemoryStore create() {
    return new MarkdownMemoryStore(file(), FIXED);
  }

  @Override
  protected LongTermMemoryStore reopen() {
    return new MarkdownMemoryStore(file(), FIXED);
  }

  @Test
  void firstSaveCreatesDirectoriesAndTheTwoSections() throws IOException {
    create().save(MemoryScope.CORE, "name is Alex");
    String raw = Files.readString(file(), StandardCharsets.UTF_8);
    assertTrue(raw.contains("## 核心记忆"));
    assertTrue(raw.contains("## 归档记忆"));
  }

  @Test
  void entriesLandUnderTheirOwnHeading() throws IOException {
    LongTermMemoryStore store = create();
    store.save(MemoryScope.ARCHIVAL, "archive first");
    store.save(MemoryScope.CORE, "core later");
    store.save(MemoryScope.ARCHIVAL, "archive second");
    String raw = Files.readString(file(), StandardCharsets.UTF_8);
    int coreHeading = raw.indexOf("## 核心记忆");
    int archivalHeading = raw.indexOf("## 归档记忆");
    assertTrue(coreHeading < raw.indexOf("core later"));
    assertTrue(raw.indexOf("core later") < archivalHeading);
    assertTrue(archivalHeading < raw.indexOf("archive first"));
    assertTrue(raw.indexOf("archive first") < raw.indexOf("archive second"));
    assertTrue(raw.contains("- [2026-03-04] core later"));
  }

  @Test
  void readingAMissingFileDoesNotCreateIt() {
    LongTermMemoryStore store = create();
    assertTrue(store.readCore().isEmpty());
    assertTrue(store.readArchival(5).isEmpty());
    assertFalse(Files.exists(file()));
  }

  @Test
  void handWrittenNotesAndDatelessBulletsSurviveAWrite() throws IOException {
    Files.createDirectories(file().getParent());
    Files.writeString(
        file(),
        "# My notes\n\n## 核心记忆\n\n- [2025-12-31] old core\n- manual core bullet\n\n"
            + "## 归档记忆\n\nfree text a person typed\n- [2025-12-30] old archive\n",
        StandardCharsets.UTF_8);
    LongTermMemoryStore store = create();
    store.save(MemoryScope.CORE, "new core");
    store.save(MemoryScope.ARCHIVAL, "new archive");

    String raw = Files.readString(file(), StandardCharsets.UTF_8);
    assertTrue(raw.contains("# My notes"));
    assertTrue(raw.contains("free text a person typed"));
    assertEquals(
        List.of("old core", "manual core bullet", "new core"),
        store.readCore().stream().map(e -> e.content()).toList());
    assertEquals(
        List.of("old archive", "new archive"),
        store.readArchival(10).stream().map(e -> e.content()).toList());
    assertEquals(null, store.readCore().get(1).savedOn(), "dateless bullet has no date");
  }

  @Test
  void malformedFileIsReportedAndNeverOverwritten() throws IOException {
    Files.createDirectories(file().getParent());
    String original = "just some text, no headings\n";
    Files.writeString(file(), original, StandardCharsets.UTF_8);
    LongTermMemoryStore store = create();
    assertThrows(MemoryStoreException.class, () -> store.save(MemoryScope.CORE, "x"));
    assertThrows(MemoryStoreException.class, store::readCore);
    assertEquals(original, Files.readString(file(), StandardCharsets.UTF_8));
  }

  @Test
  void archivalHeadingBeforeCoreIsTreatedAsMalformed() throws IOException {
    Files.createDirectories(file().getParent());
    Files.writeString(file(), "## 归档记忆\n\n## 核心记忆\n", StandardCharsets.UTF_8);
    assertThrows(MemoryStoreException.class, () -> create().readCore());
  }

  @Test
  void unreadableFilesSurfaceAsMemoryStoreException() throws IOException {
    Files.createDirectories(file().getParent());
    Files.write(file(), new byte[] {(byte) 0xC3, (byte) 0x28, (byte) 0xFF}); // invalid UTF-8
    assertThrows(MemoryStoreException.class, () -> create().readCore());

    Files.delete(file());
    Files.createDirectory(file()); // a directory where the file should be
    assertThrows(MemoryStoreException.class, () -> create().save(MemoryScope.CORE, "x"));
  }

  @Test
  void noTempFilesAreLeftBehind() throws IOException {
    LongTermMemoryStore store = create();
    for (int i = 0; i < 5; i++) {
      store.save(MemoryScope.ARCHIVAL, "n" + i);
    }
    try (var files = Files.list(file().getParent())) {
      assertEquals(List.of("MEMORY.md"), files.map(p -> p.getFileName().toString()).toList());
    }
  }

  @Test
  void aSecondWriterSeesAndKeepsTheFirstWritersEntries() {
    LongTermMemoryStore a = create();
    LongTermMemoryStore b = reopen();
    a.save(MemoryScope.ARCHIVAL, "from a");
    b.save(MemoryScope.ARCHIVAL, "from b");
    a.save(MemoryScope.ARCHIVAL, "from a again");
    assertEquals(
        List.of("from a", "from b", "from a again"),
        a.readArchival(10).stream().map(e -> e.content()).toList());
  }
}
