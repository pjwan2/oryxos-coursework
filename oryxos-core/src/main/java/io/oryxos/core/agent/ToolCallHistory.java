package io.oryxos.core.agent;

import java.time.Instant;
import java.util.List;

/** Read side of the tool audit. {@link ToolCallAudit} is the write side. */
public interface ToolCallHistory {
  /**
   * Newest first. A null {@code sessionId}, {@code since} or {@code until} means "no restriction";
   * the time bounds are inclusive.
   */
  List<ToolCallRecord> find(String sessionId, Instant since, Instant until, int limit);
}
