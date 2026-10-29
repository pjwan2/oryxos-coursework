package io.oryxos.core.memory;

import java.util.Locale;

/**
 * CORE entries are always loaded in full; ARCHIVAL entries are truncated for the prompt and are the
 * only ones searched by keyword.
 */
public enum MemoryScope {
  CORE,
  ARCHIVAL;

  /** Case-insensitive; blank or null selects ARCHIVAL (the safe default). */
  public static MemoryScope parseOrDefault(String raw) {
    if (raw == null || raw.isBlank()) {
      return ARCHIVAL;
    }
    try {
      return valueOf(raw.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("scope must be CORE or ARCHIVAL", e);
    }
  }
}
