package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.AgentEngine;
import org.junit.jupiter.api.Test;

class OryxOsBoundaryTest {
  @Test
  void coreExposesEngineAsAbstractionNotAsConcreteModel() {
    assertTrue(AgentEngine.class.isInterface());
  }
}
