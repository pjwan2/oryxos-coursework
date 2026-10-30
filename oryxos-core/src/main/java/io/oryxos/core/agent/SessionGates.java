package io.oryxos.core.agent;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One lock per session id, so two messages for the same conversation run one after the other
 * instead of both reading the same history and overwriting each other's answer. Different sessions
 * never wait for each other. A gate exists only while somebody holds or waits for it.
 */
final class SessionGates {
  private static final class Gate {
    final ReentrantLock lock = new ReentrantLock();
    int users;
  }

  private final Map<String, Gate> gates = new HashMap<>();

  /** Blocks until this session is free; the returned handle must be closed. */
  Handle enter(String sessionId) {
    Gate gate;
    synchronized (gates) {
      gate = gates.computeIfAbsent(sessionId, key -> new Gate());
      gate.users++;
    }
    gate.lock.lock();
    return new Handle(sessionId, gate);
  }

  int openGates() {
    synchronized (gates) {
      return gates.size();
    }
  }

  final class Handle implements AutoCloseable {
    private final String sessionId;
    private final Gate gate;

    private Handle(String sessionId, Gate gate) {
      this.sessionId = sessionId;
      this.gate = gate;
    }

    @Override
    public void close() {
      gate.lock.unlock();
      synchronized (gates) {
        if (--gate.users == 0) {
          gates.remove(sessionId);
        }
      }
    }
  }
}
