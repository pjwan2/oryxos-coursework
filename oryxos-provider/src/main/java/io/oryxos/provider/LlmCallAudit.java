package io.oryxos.provider;

/** Storage-agnostic audit port implemented by the storage module. */
public interface LlmCallAudit {
  void record(LlmCallEvent event);
}
