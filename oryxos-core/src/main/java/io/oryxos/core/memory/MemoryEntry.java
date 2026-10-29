package io.oryxos.core.memory;

import java.time.LocalDate;
import java.util.Objects;

/** One remembered fact. {@code savedOn} is null for lines a human added by hand without a date. */
public record MemoryEntry(MemoryScope scope, String content, LocalDate savedOn) {
  public MemoryEntry {
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(content, "content");
  }
}
