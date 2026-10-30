package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.tool.ToolSpec;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

class MockChatModelTest {
  private final MockChatModel model = new MockChatModel();

  private static Prompt withTools(String user, String... toolNames) {
    var specs = new ToolSchemaAdapter();
    var callbacks =
        specs.toSpringAiTools(
            java.util.Arrays.stream(toolNames)
                .map(n -> new ToolSpec(n, "d", "{\"type\":\"object\"}"))
                .toList());
    var options =
        ToolCallingChatOptions.builder()
            .toolCallbacks(callbacks)
            .internalToolExecutionEnabled(false)
            .build();
    return new Prompt(List.of(new UserMessage(user)), options);
  }

  @Test
  void plainTextIsEchoed() {
    assertEquals(
        "Mock response: hello",
        model
            .call(new Prompt(List.of(new UserMessage("hello"))))
            .getResult()
            .getOutput()
            .getText());
  }

  @Test
  void toolCommandRequestsAnAdvertisedToolWithExactArguments() {
    for (String prefix : List.of("/tool ", "tool:")) {
      AssistantMessage out =
          model
              .call(withTools(prefix + "get_payment {\"payment_id\":\"P1001\"}", "get_payment"))
              .getResult()
              .getOutput();
      assertEquals(1, out.getToolCalls().size());
      assertEquals("get_payment", out.getToolCalls().getFirst().name());
      assertEquals("{\"payment_id\":\"P1001\"}", out.getToolCalls().getFirst().arguments());
    }
  }

  @Test
  void toolThatWasNotAdvertisedIsNeverRequested() {
    AssistantMessage out =
        model.call(withTools("/tool shell_exec {}", "get_payment")).getResult().getOutput();
    assertTrue(out.getToolCalls().isEmpty());
    assertEquals("Tool not available: shell_exec", out.getText());
    assertTrue(
        model
            .call(new Prompt(List.of(new UserMessage("tool:x {}"))))
            .getResult()
            .getOutput()
            .getToolCalls()
            .isEmpty());
  }

  @Test
  void toolResultIsTurnedIntoAnAnswer() {
    var reply = new ToolResponseMessage.ToolResponse("id", "get_payment", "P1001 PENDING");
    var prompt =
        new Prompt(
            List.of(
                new UserMessage("q"),
                ToolResponseMessage.builder().responses(List.of(reply)).build()));
    assertEquals(
        "Tool result: P1001 PENDING", model.call(prompt).getResult().getOutput().getText());
  }

  @Test
  void theSamePromptAlwaysGivesTheSameAnswer() {
    Prompt prompt = withTools("tool:get_payment {\"payment_id\":\"P1\"}", "get_payment");
    var first = model.call(prompt).getResult().getOutput();
    var second = model.call(prompt).getResult().getOutput();
    assertEquals(first.getToolCalls().getFirst().id(), second.getToolCalls().getFirst().id());
    assertEquals(
        first.getToolCalls().getFirst().arguments(), second.getToolCalls().getFirst().arguments());
  }

  @Test
  void afterAToolResultTheMockAnswersWithTextAndNeverAsksForAnotherToolCall() {
    // Two model calls per tool round trip: the loop in the week-5 end-to-end tests relies on this.
    var response =
        new ToolResponseMessage.ToolResponse("mock-call-get_payment", "get_payment", "PENDING");
    Prompt prompt =
        new Prompt(
            List.of(
                new UserMessage("tool:get_payment {\"payment_id\":\"P1\"}"),
                ToolResponseMessage.builder().responses(List.of(response)).build()));
    AssistantMessage out = model.call(prompt).getResult().getOutput();
    assertTrue(out.getToolCalls().isEmpty());
    assertEquals("Tool result: PENDING", out.getText());
  }
}
