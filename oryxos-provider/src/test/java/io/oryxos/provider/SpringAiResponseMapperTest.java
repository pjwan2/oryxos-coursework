package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.oryxos.core.agent.ModelTurn;
import io.oryxos.core.agent.ToolAction;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;

class SpringAiResponseMapperTest {
  private final SpringAiResponseMapper mapper = new SpringAiResponseMapper();

  private static ChatResponse responseWith(AssistantMessage message) {
    return new ChatResponse(List.of(new Generation(message)));
  }

  private static AssistantMessage.ToolCall call(String id, String name, String arguments) {
    return new AssistantMessage.ToolCall(id, "function", name, arguments);
  }

  @Test
  void plainTextBecomesFinalAnswerWithoutActions() {
    ModelTurn turn = mapper.map(responseWith(new AssistantMessage("P1001 is pending")));

    assertEquals("P1001 is pending", turn.text());
    assertTrue(turn.actions().isEmpty());
  }

  @Test
  void toolCallKeepsIdNameAndExactArguments() {
    String arguments = "{\"payment_id\":\"P1001\"}";
    AssistantMessage message =
        AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(call("call-001", "get_payment", arguments)))
            .build();

    ModelTurn turn = mapper.map(responseWith(message));

    assertEquals(1, turn.actions().size());
    assertEquals(new ToolAction("call-001", "get_payment", arguments), turn.actions().getFirst());
  }

  @Test
  void multipleToolCallsKeepOrderAndTheirOwnArguments() {
    AssistantMessage message =
        AssistantMessage.builder()
            .content("checking")
            .toolCalls(
                List.of(
                    call("call-1", "get_payment", "{\"payment_id\":\"P1001\"}"),
                    call("call-2", "get_payment", "{\"payment_id\":\"P1002\"}")))
            .build();

    ModelTurn turn = mapper.map(responseWith(message));

    assertEquals("checking", turn.text());
    assertEquals(
        List.of(
            new ToolAction("call-1", "get_payment", "{\"payment_id\":\"P1001\"}"),
            new ToolAction("call-2", "get_payment", "{\"payment_id\":\"P1002\"}")),
        turn.actions());
  }

  @Test
  void missingOrEmptyArgumentsBecomeEmptyJsonObject() {
    AssistantMessage message =
        AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(call("c1", "list_payments", ""), call("c2", "ping", null)))
            .build();

    ModelTurn turn = mapper.map(responseWith(message));

    assertEquals("{}", turn.actions().get(0).argumentsJson());
    assertEquals("{}", turn.actions().get(1).argumentsJson());
  }

  @Test
  void nullTextBecomesEmptyString() {
    AssistantMessage message =
        AssistantMessage.builder().toolCalls(List.of(call("c1", "get_payment", "{}"))).build();

    assertEquals("", mapper.map(responseWith(message)).text());
  }

  @Test
  void nullResponseIsRejectedWithClearMessage() {
    IllegalStateException error = assertThrows(IllegalStateException.class, () -> mapper.map(null));
    assertEquals("LLM response is null", error.getMessage());
  }

  @Test
  void responseWithoutGenerationIsRejected() {
    ChatResponse response = mock(ChatResponse.class);
    when(response.getResult()).thenReturn(null);

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> mapper.map(response));
    assertEquals("LLM response has no generation", error.getMessage());
  }

  @Test
  void generationWithoutAssistantMessageIsRejected() {
    Generation generation = mock(Generation.class);
    when(generation.getOutput()).thenReturn(null);
    ChatResponse response = mock(ChatResponse.class);
    when(response.getResult()).thenReturn(generation);

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> mapper.map(response));
    assertEquals("LLM response has no assistant message", error.getMessage());
  }

  @Test
  void toolCallWithoutNameOrIdIsRejectedInsteadOfExecutedBlindly() {
    AssistantMessage noName =
        AssistantMessage.builder().content("").toolCalls(List.of(call("c1", " ", "{}"))).build();
    AssistantMessage noId =
        AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(call("", "get_payment", "{}")))
            .build();

    assertEquals(
        "LLM tool call #0 has no tool name",
        assertThrows(IllegalStateException.class, () -> mapper.map(responseWith(noName)))
            .getMessage());
    assertEquals(
        "LLM tool call #0 has no id",
        assertThrows(IllegalStateException.class, () -> mapper.map(responseWith(noId)))
            .getMessage());
  }

  @Test
  void mapperNeverInvokesAToolCallback() {
    ToolCallback callback = mock(ToolCallback.class);
    AssistantMessage message =
        AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(call("c1", "get_payment", "{\"payment_id\":\"P1001\"}")))
            .build();

    mapper.map(responseWith(message));

    verifyNoInteractions(callback);
  }
}
