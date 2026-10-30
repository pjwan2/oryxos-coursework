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

  /**
   * Exactly the calls made by one run, newest first. Unlike {@link #find} this does not depend on
   * time, so two runs of the same agent that overlap in time are never mixed up.
   */
  default List<ToolCallRecord> findByExecution(String executionId, int limit) {
    throw new UnsupportedOperationException("This history cannot look calls up by execution id");
  }
}
