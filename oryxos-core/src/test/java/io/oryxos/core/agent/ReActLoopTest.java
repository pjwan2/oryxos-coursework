package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.tool.ToolSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReActLoopTest {
  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC);

  private static AgentProfile profile(int maxIterations) {
    return new AgentProfile(
        "demo",
        "lesson17",
        Map.of("prompt", "system-rules"),
        new AgentProfile.ProviderSettings("mock", "m", 0.2),
        List.of("get_payment"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        Map.of("max_iterations", maxIterations));
  }

  private static AgentToolExecutor tools(AtomicInteger calls) {
    return new AgentToolExecutor() {
      @Override
      public List<ToolSpec> allowedSpecifications(AgentProfile profile) {
        return List.of(new ToolSpec("get_payment", "Payment lookup", "{\"type\":\"object\"}"));
      }

      @Override
      public ToolOutput execute(String sessionId, AgentProfile profile, ToolAction action) {
        calls.incrementAndGet();
        return ToolOutput.ok("paid");
      }
    };
  }

  @Test
  void noToolCallStopsAfterOneModelCall() {
    AtomicInteger calls = new AtomicInteger();
    ModelGateway model =
        (id, cfg, prompts, specs) -> {
          calls.incrementAndGet();
          return ModelTurn.finalAnswer("Hello");
        };
    AgentSession session = new AgentSession("s1", "demo");
    String result =
        new ReActLoop(model, tools(new AtomicInteger()), new PromptBuilder(FIXED, 20))
            .run(session, "hi", profile(10));
    assertEquals("Hello", result);
    assertEquals(1, calls.get());
    assertEquals(2, session.transcript().size());
  }

  @Test
  void toolResultIsReturnedToNextModelTurn() {
    List<List<AgentMessage>> seen = new ArrayList<>();
    AtomicInteger toolCalls = new AtomicInteger();
    ModelGateway model =
        (id, cfg, prompts, specs) -> {
          seen.add(prompts);
          if (seen.size() == 1) {
            return ModelTurn.tools(List.of(new ToolAction("t1", "get_payment", "{}")));
          }
          assertTrue(
              prompts.stream()
                  .anyMatch(m -> m.content().contains("TOOL_RESULT t1 get_payment paid")));
          return ModelTurn.finalAnswer("done");
        };
    AgentSession session = new AgentSession("s2", "demo");
    String result =
        new ReActLoop(model, tools(toolCalls), new PromptBuilder(FIXED, 20))
            .run(session, "lookup", profile(10));
    assertEquals("done", result);
    assertEquals(2, seen.size());
    assertEquals(1, toolCalls.get());
    assertEquals(4, session.transcript().size());
  }

  @Test
  void repeatedToolCallsCannotLoopForever() {
    AtomicInteger modelCalls = new AtomicInteger();
    ModelGateway model =
        (id, cfg, prompts, specs) -> {
          modelCalls.incrementAndGet();
          return ModelTurn.tools(List.of(new ToolAction("t", "get_payment", "{}")));
        };
    String output =
        new ReActLoop(model, tools(new AtomicInteger()), new PromptBuilder(FIXED, 20))
            .run(new AgentSession("s3", "demo"), "lookup", profile(3));
    assertTrue(output.contains("最大轮数"));
    assertEquals(3, modelCalls.get());
  }

  @Test
  void promptIncludesCurrentDateAndTruncatesHistory() {
    AgentSession session = new AgentSession("s4", "demo");
    for (int i = 0; i < 9; i++) {
      session.append(new AgentMessage(AgentMessage.Role.USER, "item-" + i));
    }
    List<AgentMessage> prompt = new PromptBuilder(FIXED, 3).build(session, profile(10));
    assertEquals(4, prompt.size());
    assertTrue(prompt.getFirst().content().contains("2026-10-28"));
    assertTrue(prompt.getFirst().content().contains("system-rules"));
    assertEquals("item-6", prompt.get(1).content());
  }

  @Test
  void profileMismatchFailsBeforeCallingModel() {
    ModelGateway noCall =
        (id, cfg, prompt, specs) -> {
          throw new AssertionError("Must not call model");
        };
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReActLoop(noCall, tools(new AtomicInteger()), new PromptBuilder(FIXED, 20))
                .run(new AgentSession("x", "another"), "hello", profile(10)));
  }
}
