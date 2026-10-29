package io.oryxos.core.agent;

/** Tool audit must be durable and independent from the model-call audit. */
@FunctionalInterface
public interface ToolCallAudit {
  void record(ToolCallEvent event);
}
