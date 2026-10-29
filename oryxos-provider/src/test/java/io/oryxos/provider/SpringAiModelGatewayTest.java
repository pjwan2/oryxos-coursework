package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.oryxos.core.agent.AgentMessage;
import io.oryxos.core.agent.ModelTurn;
import io.oryxos.core.agent.ToolAction;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.tool.ToolSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class SpringAiModelGatewayTest {
  private static final String SESSION = "web:u1:payment-real";
  private static final AgentProfile PROFILE = AgentProfile.example("deepseek", "deepseek-chat");
  private static final List<ToolSpec> TOOLS =
      List.of(new ToolSpec("get_payment", "Lookup a payment", "{\"type\":\"object\"}"));
  private static final List<AgentMessage> TRANSCRIPT =
      List.of(
          new AgentMessage(AgentMessage.Role.SYSTEM, "You are a payment assistant"),
          new AgentMessage(AgentMessage.Role.USER, "Look up P1001"));

  private final ProviderService provider = mock(ProviderService.class);
  private final SpringAiModelGateway gateway = new SpringAiModelGateway(provider);

  private static ChatResponse response(AssistantMessage message) {
    return new ChatResponse(List.of(new Generation(message)));
  }

  @Test
  void plainTextReplyBecomesTurnWithoutActions() {
    when(provider.chat(eq(SESSION), eq(PROFILE), any(Prompt.class), eq(TOOLS)))
        .thenReturn(response(new AssistantMessage("P1001 is PENDING")));

    ModelTurn turn = gateway.chat(SESSION, PROFILE, TRANSCRIPT, TOOLS);

    assertEquals("P1001 is PENDING", turn.text());
    assertTrue(turn.actions().isEmpty());
  }

  @Test
  void toolRequestKeepsIdNameAndArguments() {
    String arguments = "{\"payment_id\":\"P1001\"}";
    var requested =
        AssistantMessage.builder()
            .content("")
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall(
                        "call-001", "function", "get_payment", arguments)))
            .build();
    when(provider.chat(eq(SESSION), eq(PROFILE), any(Prompt.class), eq(TOOLS)))
        .thenReturn(response(requested));

    ModelTurn turn = gateway.chat(SESSION, PROFILE, TRANSCRIPT, TOOLS);

    assertEquals(List.of(new ToolAction("call-001", "get_payment", arguments)), turn.actions());
  }

  @Test
  void providerFailureIsPropagatedUnchanged() {
    IllegalStateException timeout = new IllegalStateException("upstream timeout");
    when(provider.chat(any(), any(), any(Prompt.class), any())).thenThrow(timeout);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> gateway.chat(SESSION, PROFILE, TRANSCRIPT, TOOLS));

    assertSame(timeout, thrown);
  }

  @Test
  void providerFailureIsAuditedAsFailedByProviderLayerAndStillReachesCaller() {
    ChatModel model = mock(ChatModel.class);
    when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("secret-bearing text"));
    List<LlmCallEvent> audit = new ArrayList<>();
    var realProvider =
        new ProviderService(Map.of("deepseek", model), new ToolSchemaAdapter(), audit::add);

    assertThrows(
        IllegalStateException.class,
        () -> new SpringAiModelGateway(realProvider).chat(SESSION, PROFILE, TRANSCRIPT, TOOLS));

    assertEquals(1, audit.size());
    assertFalse(audit.getFirst().success());
  }

  @Test
  void gatewayOnlyAsksProviderForOneCallAndExecutesNothing() {
    var requested =
        AssistantMessage.builder()
            .content("")
            .toolCalls(
                List.of(new AssistantMessage.ToolCall("c1", "function", "get_payment", "{}")))
            .build();
    when(provider.chat(any(), any(), any(Prompt.class), any())).thenReturn(response(requested));

    ModelTurn turn = gateway.chat(SESSION, PROFILE, TRANSCRIPT, TOOLS);

    // The tool request is only handed back for ReActLoop; the single interaction is one chat call.
    assertEquals(1, turn.actions().size());
    verify(provider).chat(eq(SESSION), eq(PROFILE), any(Prompt.class), eq(TOOLS));
    verifyNoMoreInteractions(provider);
  }

  @Test
  void toolCallIdSurvivesIntoTheNextPromptSoResultsCanBeCorrelated() {
    var calls = List.of(new ToolAction("call-001", "get_payment", "{\"payment_id\":\"P1001\"}"));
    List<AgentMessage> history =
        List.of(
            new AgentMessage(AgentMessage.Role.USER, "Look up P1001"),
            AgentMessage.requests(calls),
            AgentMessage.replies(
                List.of(
                    new AgentMessage.ToolReply(
                        "call-001", "get_payment", "P1001 PENDING 120 AUD"))));
    when(provider.chat(any(), any(), any(Prompt.class), any()))
        .thenReturn(response(new AssistantMessage("done")));

    gateway.chat(SESSION, PROFILE, history, TOOLS);

    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(provider).chat(any(), any(), captor.capture(), any());
    List<Message> sent = captor.getValue().getInstructions();
    assertInstanceOf(UserMessage.class, sent.get(0));
    var assistant = assertInstanceOf(AssistantMessage.class, sent.get(1));
    assertEquals("call-001", assistant.getToolCalls().getFirst().id());
    assertEquals("get_payment", assistant.getToolCalls().getFirst().name());
    assertEquals("{\"payment_id\":\"P1001\"}", assistant.getToolCalls().getFirst().arguments());
    var tool = assertInstanceOf(ToolResponseMessage.class, sent.get(2));
    assertEquals("call-001", tool.getResponses().getFirst().id());
    assertEquals("P1001 PENDING 120 AUD", tool.getResponses().getFirst().responseData());
  }

  @Test
  void systemAndUserRolesAreMappedToNativeMessages() {
    when(provider.chat(any(), any(), any(Prompt.class), any()))
        .thenReturn(response(new AssistantMessage("ok")));

    gateway.chat(SESSION, PROFILE, TRANSCRIPT, TOOLS);

    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(provider).chat(any(), any(), captor.capture(), any());
    List<Message> sent = captor.getValue().getInstructions();
    assertInstanceOf(SystemMessage.class, sent.get(0));
    assertEquals("You are a payment assistant", sent.get(0).getText());
    assertInstanceOf(UserMessage.class, sent.get(1));
  }
}
