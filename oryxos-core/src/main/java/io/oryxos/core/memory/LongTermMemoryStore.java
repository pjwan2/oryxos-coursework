package io.oryxos.core.memory;

import java.util.List;

/**
 * Pluggable long-term memory backend. Contract every implementation must keep (see
 * MemoryStoreContractTest):
 *
 * <ol>
 *   <li>A saved entry is visible to the very next read; no caching.
 *   <li>{@link #readCore()} returns every CORE entry, never truncated.
 *   <li>{@link #readArchival} and {@link #searchArchival} only ever see ARCHIVAL entries.
 *   <li>Lists are in save order (oldest first); a {@code limit} keeps the MOST RECENT entries.
 *   <li>Keyword search is case-insensitive substring matching.
 *   <li>Content is validated with {@link MemoryText#normalize}; invalid input throws {@link
 *       IllegalArgumentException}, backend failures throw {@link MemoryStoreException}.
 * </ol>
 */
public interface LongTermMemoryStore {
  void save(MemoryScope scope, String content);

  List<MemoryEntry> readCore();

  List<MemoryEntry> readArchival(int limit);

  List<MemoryEntry> searchArchival(String keyword, int limit);
}
