package io.oryxos.memory;

import io.oryxos.core.memory.LongTermMemoryStore;
import io.oryxos.core.memory.MemoryEntry;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryStoreException;
import io.oryxos.core.memory.MemoryText;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One Markdown file with a core section followed by an archival section. The file is re-read on
 * every call (no cache) so a human edit or another process is always seen, and written through a
 * temp file + atomic move so a crash cannot leave half a file. Lines this class does not understand
 * (notes a person typed in) are kept untouched. A file missing either heading is never rewritten.
 */
public final class MarkdownMemoryStore implements LongTermMemoryStore {
  static final String CORE_HEADING = "## 核心记忆";
  static final String ARCHIVAL_HEADING = "## 归档记忆";
  private static final Pattern BULLET =
      Pattern.compile("^- (?:\\[(\\d{4}-\\d{2}-\\d{2})\\] )?(.+)$");

  private static final Logger LOG = LoggerFactory.getLogger(MarkdownMemoryStore.class);
  private final Path file;
  private final Clock clock;

  public MarkdownMemoryStore(Path file, Clock clock) {
    this.file = Objects.requireNonNull(file, "file");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public synchronized void save(MemoryScope scope, String content) {
    Objects.requireNonNull(scope, "scope");
    String line = "- [" + LocalDate.now(clock) + "] " + MemoryText.normalize(content);
    List<String> lines = readOrCreate();
    int coreAt = headingIndex(lines, CORE_HEADING);
    int archivalAt = headingIndex(lines, ARCHIVAL_HEADING);
    int insertAt;
    if (scope == MemoryScope.CORE) {
      insertAt = lastContentLine(lines, coreAt + 1, archivalAt) + 1;
    } else {
      insertAt = lastContentLine(lines, archivalAt + 1, lines.size()) + 1;
    }
    lines.add(insertAt, line);
    write(lines);
  }

  @Override
  public synchronized List<MemoryEntry> readCore() {
    return entries(MemoryScope.CORE);
  }

  @Override
  public synchronized List<MemoryEntry> readArchival(int limit) {
    return tail(entries(MemoryScope.ARCHIVAL), limit);
  }

  @Override
  public synchronized List<MemoryEntry> searchArchival(String keyword, int limit) {
    String needle = MemoryText.keyword(keyword).toLowerCase(Locale.ROOT);
    List<MemoryEntry> matches =
        entries(MemoryScope.ARCHIVAL).stream()
            .filter(e -> e.content().toLowerCase(Locale.ROOT).contains(needle))
            .toList();
    return tail(matches, limit);
  }

  private List<MemoryEntry> entries(MemoryScope scope) {
    if (!Files.exists(file)) {
      return List.of();
    }
    List<String> lines = read();
    int coreAt = headingIndex(lines, CORE_HEADING);
    int archivalAt = headingIndex(lines, ARCHIVAL_HEADING);
    int from = scope == MemoryScope.CORE ? coreAt + 1 : archivalAt + 1;
    int to = scope == MemoryScope.CORE ? archivalAt : lines.size();
    List<MemoryEntry> result = new ArrayList<>();
    for (int i = from; i < to; i++) {
      Matcher m = BULLET.matcher(lines.get(i).stripTrailing());
      if (m.matches()) {
        result.add(new MemoryEntry(scope, m.group(2), parseDate(m.group(1))));
      }
    }
    return List.copyOf(result);
  }

  private static LocalDate parseDate(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return LocalDate.parse(raw);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static List<MemoryEntry> tail(List<MemoryEntry> all, int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive");
    }
    return all.size() <= limit ? all : List.copyOf(all.subList(all.size() - limit, all.size()));
  }

  /** Index of the heading line; the file must contain core before archival. */
  private int headingIndex(List<String> lines, String heading) {
    int found = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).strip().equals(heading)) {
        found = i;
        break;
      }
    }
    int core = indexOfStripped(lines, CORE_HEADING);
    int archival = indexOfStripped(lines, ARCHIVAL_HEADING);
    if (found < 0 || core < 0 || archival < 0 || core > archival) {
      throw new MemoryStoreException(
          file
              + " is malformed: expected '"
              + CORE_HEADING
              + "' followed by '"
              + ARCHIVAL_HEADING
              + "'");
    }
    return found;
  }

  private static int indexOfStripped(List<String> lines, String heading) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).strip().equals(heading)) {
        return i;
      }
    }
    return -1;
  }

  /** Last non-blank line index in [from, to), or from-1 when the range holds only blanks. */
  private static int lastContentLine(List<String> lines, int from, int to) {
    for (int i = to - 1; i >= from; i--) {
      if (!lines.get(i).isBlank()) {
        return i;
      }
    }
    return from - 1;
  }

  /** Directory holding the file; a file path with no parent (the filesystem root) is invalid. */
  private Path directory() {
    Path parent = file.toAbsolutePath().getParent();
    if (parent == null) {
      throw new MemoryStoreException("Invalid memory file path: " + file);
    }
    return parent;
  }

  private List<String> readOrCreate() {
    if (!Files.exists(file)) {
      try {
        Files.createDirectories(directory());
        Files.writeString(
            file,
            CORE_HEADING + "\n\n" + ARCHIVAL_HEADING + "\n",
            StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE_NEW);
      } catch (FileAlreadyExistsException raced) {
        // Another writer created it first; read what they wrote.
        LOG.debug("{} was created concurrently", file);
      } catch (IOException e) {
        throw new MemoryStoreException("Unable to create " + file, e);
      }
    }
    return read();
  }

  private List<String> read() {
    try {
      return new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new MemoryStoreException("Unable to read " + file, e);
    }
  }

  private void write(List<String> lines) {
    Path directory = directory();
    Path temp = null;
    try {
      temp = Files.createTempFile(directory, "memory-", ".tmp");
      Files.writeString(temp, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
      try {
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new MemoryStoreException("Unable to write " + file, e);
    } finally {
      if (temp != null) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException leftover) {
          // The temp file is only garbage; the real file is already consistent.
          LOG.debug("Could not delete temp file {}", temp);
        }
      }
    }
  }
}
