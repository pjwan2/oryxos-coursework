package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.AgentMessage;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.AgentSession;
import io.oryxos.core.agent.AgentSessionStore;
import io.oryxos.core.agent.PromptBuilder;
import io.oryxos.core.memory.LongTermMemoryStore;
import io.oryxos.provider.ProfileRegistry;
import io.oryxos.storage.ToolInvocationRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Full Spring context, mock model, real tools. Subclasses only choose the memory backend, so the
 * same assertions prove the upper layers do not change when the backend does.
 */
abstract class AbstractMemoryPromptIntegrationTest {
  @Autowired
  @Qualifier("springAiAgentService")
  AgentService agent;

  @Autowired AgentSessionStore sessions;
  @Autowired PromptBuilder prompts;
  @Autowired ProfileRegistry profiles;
  @Autowired LongTermMemoryStore store;
  @Autowired ToolInvocationRepository toolAudit;

  static Path workDir() {
    try {
      return Files.createTempDirectory("oryxos-memory-it");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String user() {
    return "m" + UUID.randomUUID().toString().replace("-", "");
  }

  private String systemPromptFor(String profileName) {
    String id = "cli:" + user() + ":" + profileName;
    var prompt = prompts.build(new AgentSession(id, profileName), profiles.get(profileName));
    assertEquals(AgentMessage.Role.SYSTEM, prompt.getFirst().role());
    return prompt.getFirst().content();
  }

  @Test
  void coreFactSavedThroughTheToolIsInTheSystemPromptOfABrandNewSession() {
    String fact = "likes oolong tea " + UUID.randomUUID();
    String reply =
        agent.process(
            "web",
            user(),
            "memory-demo",
            "tool:save_memory {\"content\":\"" + fact + "\",\"scope\":\"CORE\"}");
    assertEquals("Tool result: Saved to CORE memory", reply);

    String system = systemPromptFor("memory-demo");
    assertTrue(system.contains(fact), "next conversation must see the new core memory");
    assertTrue(system.contains("### Core"));
  }

  @Test
  void archivalFactsAreRecalledByKeywordButCoreFactsAreNot() {
    String archival = "met Dana at the harbour " + UUID.randomUUID();
    String core = "harbour is my favourite place " + UUID.randomUUID();
    String u = user();
    agent.process("web", u, "memory-demo", "tool:save_memory {\"content\":\"" + archival + "\"}");
    agent.process(
        "web",
        u,
        "memory-demo",
        "tool:save_memory {\"content\":\"" + core + "\",\"scope\":\"CORE\"}");

    String reply =
        agent.process("web", u, "memory-demo", "tool:recall_memory {\"keyword\":\"harbour\"}");
    assertTrue(reply.contains(archival));
    assertFalse(reply.contains(core), "recall does not search CORE");
  }

  @Test
  void conversationHistoryIsNotCopiedIntoTheMemorySection() {
    String marker = "CHAT-MARKER-" + UUID.randomUUID();
    String u = user();
    agent.process(
        "web",
        u,
        "memory-demo",
        "tool:save_memory {\"content\":\"a core fact\",\"scope\":\"CORE\"}");
    agent.process("web", u, "memory-demo", "just talking about " + marker);
    assertFalse(systemPromptFor("memory-demo").contains(marker));
  }

  @Test
  void plainConversationNeverCreatesLongTermMemoryOnItsOwn() {
    int coreBefore = store.readCore().size();
    int archivalBefore = store.readArchival(10_000).size();
    agent.process(
        "web", user(), "memory-demo", "My name is Alex and I love green tea, remember it please");
    assertEquals(coreBefore, store.readCore().size());
    assertEquals(archivalBefore, store.readArchival(10_000).size());
  }

  @Test
  void anAgentWithoutTheMemoryToolsCannotUseThemAndSeesNoMemory() {
    String secret = "private-note-" + UUID.randomUUID();
    agent.process(
        "web",
        user(),
        "memory-demo",
        "tool:save_memory {\"content\":\"" + secret + "\",\"scope\":\"CORE\"}");
    int before = store.readCore().size();

    String reply =
        agent.process(
            "web",
            user(),
            "payment-demo",
            "tool:save_memory {\"content\":\"injected\",\"scope\":\"CORE\"}");
    assertEquals("Tool not available: save_memory", reply);
    assertEquals(before, store.readCore().size(), "nothing may be written");
    assertFalse(systemPromptFor("payment-demo").contains(secret), "memory is opt-in per Profile");
  }

  @Test
  void toolAuditAndSessionPersistenceStillWorkForMemoryTools() {
    String u = user();
    agent.process("web", u, "memory-demo", "tool:save_memory {\"content\":\"audited\"}");
    String id = "web:" + u + ":memory-demo";
    var rows = toolAudit.findBySessionId(id);
    assertEquals(1, rows.size());
    assertEquals("save_memory", rows.getFirst().getToolName());
    assertTrue(rows.getFirst().isSuccess());
    assertEquals(4, sessions.find(id).orElseThrow().transcript().size());
  }

  @Test
  void invalidScopeIsAFailedToolCallReportedToTheModelAndAudited() {
    String u = user();
    String reply =
        agent.process(
            "web", u, "memory-demo", "tool:save_memory {\"content\":\"x\",\"scope\":\"FOREVER\"}");
    assertTrue(reply.contains("ERROR scope must be CORE or ARCHIVAL"), reply);
    List<?> rows = toolAudit.findBySessionId("web:" + u + ":memory-demo");
    assertEquals(1, rows.size());
  }

  @Test
  void noUserMdIsEverWritten() {
    agent.process(
        "web",
        user(),
        "memory-demo",
        "tool:save_memory {\"content\":\"anything\",\"scope\":\"CORE\"}");
    assertFalse(Files.exists(Path.of("USER.md")));
    assertFalse(Files.exists(Path.of(".oryxos", "USER.md")));
    assertFalse(Files.exists(Path.of(".oryxos", "memory", "USER.md")));
  }
}
