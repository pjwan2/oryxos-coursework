package io.oryxos.core;

/** Stable upper-layer abstraction, intentionally no LLM dependency in core. */
public interface AgentEngine {
  String process(String sessionId, String userMessage);
}
