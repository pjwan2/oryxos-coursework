package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.profile.AgentProfile;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromptBuilderTest {
  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-01-02T00:00:00Z"), ZoneOffset.UTC);
  private static final AgentProfile PROFILE = AgentProfile.example("mock", "m");

  private static AgentSession session(AgentMessage... messages) {
    var s = new AgentSession("cli:u:test-agent", "test-agent");
    for (AgentMessage m : messages) {
      s.append(m);
    }
    return s;
  }

  @Test
  void keepsOnlyTheLastNMessagesAfterTheSystemPrompt() {
    var s =
        session(
            new AgentMessage(AgentMessage.Role.USER, "1"),
            new AgentMessage(AgentMessage.Role.ASSISTANT, "2"),
            new AgentMessage(AgentMessage.Role.USER, "3"));
    List<AgentMessage> prompt = new PromptBuilder(FIXED, 2).build(s, PROFILE);
    assertEquals(3, prompt.size());
    assertEquals(AgentMessage.Role.SYSTEM, prompt.getFirst().role());
    assertEquals("2", prompt.get(1).content());
  }

  @Test
  void truncationNeverStartsWithAnOrphanToolReply() {
    var call = new ToolAction("c1", "t", "{}");
    var s =
        session(
            new AgentMessage(AgentMessage.Role.USER, "q"),
            AgentMessage.requests(List.of(call)),
            AgentMessage.replies(List.of(new AgentMessage.ToolReply("c1", "t", "r"))),
            new AgentMessage(AgentMessage.Role.ASSISTANT, "answer"));
    // Window of 2 would start at the TOOL reply whose tool call was cut off.
    List<AgentMessage> prompt = new PromptBuilder(FIXED, 2).build(s, PROFILE);
    assertEquals(2, prompt.size());
    assertEquals("answer", prompt.get(1).content());
  }

  @Test
  void contextLoadersAreAppendedToTheSystemPromptAndBlankOnesIgnored() {
    var s = session(new AgentMessage(AgentMessage.Role.USER, "hi"));
    var builder =
        new PromptBuilder(FIXED, 5, List.of((p, sess) -> "MEMORY: likes tea", (p, sess) -> " "));
    String system = builder.build(s, PROFILE).getFirst().content();
    assertTrue(system.contains("MEMORY: likes tea"));
    assertTrue(system.contains("Current date: 2026-01-02"));
    assertEquals(1, system.lines().filter(l -> l.startsWith("MEMORY")).count());
  }
}
