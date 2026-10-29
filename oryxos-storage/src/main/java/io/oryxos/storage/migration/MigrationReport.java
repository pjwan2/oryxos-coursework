package io.oryxos.storage.migration;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a startup migration did. {@code rowCounts} are the rows of every pre-existing table, taken
 * before and after, which is how "no history was lost" is checked.
 */
public record MigrationReport(
    boolean freshDatabase,
    int fromVersion,
    int toVersion,
    List<Integer> applied,
    Path backupFile,
    Map<String, Long> rowCountsBefore,
    Map<String, Long> rowCountsAfter) {
  public MigrationReport {
    applied = List.copyOf(applied);
    rowCountsBefore = new TreeMap<>(rowCountsBefore);
    rowCountsAfter = new TreeMap<>(rowCountsAfter);
  }

  @Override
  public List<Integer> applied() {
    return List.copyOf(applied);
  }

  @Override
  public Map<String, Long> rowCountsBefore() {
    return new TreeMap<>(rowCountsBefore);
  }

  @Override
  public Map<String, Long> rowCountsAfter() {
    return new TreeMap<>(rowCountsAfter);
  }
}
