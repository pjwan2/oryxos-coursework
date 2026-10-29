package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.profile.AgentProfile;
import io.oryxos.provider.ProviderService;
import io.oryxos.storage.LlmCallRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Boot composition root + real JPA/SQLite audit + scriptable mock model. */
@SpringBootTest
class ProviderBootIntegrationTest {
  @Autowired ProviderService service;
  @Autowired LlmCallRepository records;

  @Test
  void mockModelCallsArePersistedInSqlite() {
    String session = "integration-" + UUID.randomUUID();
    var reply =
        service.chat(session, AgentProfile.example("mock", "mock-echo"), new Prompt("hello world"));
    assertTrue(reply.getResult().getOutput().getText().contains("hello world"));
    var saved = records.findBySessionId(session);
    assertFalse(saved.isEmpty());
    assertTrue(saved.getFirst().isSuccess());
  }
}
