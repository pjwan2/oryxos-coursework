package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.AgentSessionStore;
import io.oryxos.provider.ProfileRegistry;
import io.oryxos.storage.ToolInvocationRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Full Spring + SQLite audit and session storage, without external API key. */
@SpringBootTest(properties = "oryxos.profile-directory=../.oryxos/profiles")
class PaymentLabIntegrationTest {
  @Autowired AgentService agent;
  @Autowired AgentSessionStore sessions;
  @Autowired ToolInvocationRepository audit;
  @Autowired ProfileRegistry profiles;

  @Test
  void paymentLookupPersistsSessionAndOneToolAudit() {
    String user = "lab" + UUID.randomUUID().toString().replace("-", "");
    assertEquals("mock", profiles.get("payment-demo").provider().name());
    String reply = agent.process("web", user, "payment-demo", "查询支付单 P1001");
    assertTrue(reply.contains("PENDING"));
    String id = "web:" + user + ":payment-demo";
    assertEquals(4, sessions.find(id).orElseThrow().transcript().size());
    assertEquals(1, audit.findBySessionId(id).size());
    assertTrue(audit.findBySessionId(id).getFirst().isSuccess());
  }

  @Test
  void unknownPaymentIsNotReportedAsSuccess() {
    String user = "lab" + UUID.randomUUID().toString().replace("-", "");
    String reply = agent.process("web", user, "payment-demo", "查询支付单 P9999");
    assertTrue(reply.startsWith("查询失败"));
    assertFalse(audit.findBySessionId("web:" + user + ":payment-demo").getFirst().isSuccess());
  }
}
