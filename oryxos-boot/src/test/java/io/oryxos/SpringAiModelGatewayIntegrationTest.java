package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.AgentMessage;
import io.oryxos.core.agent.AgentSession;
import io.oryxos.core.agent.PromptBuilder;
import io.oryxos.core.agent.ReActLoop;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.provider.LlmCallEvent;
import io.oryxos.provider.ProviderService;
import io.oryxos.provider.SpringAiModelGateway;
import io.oryxos.provider.ToolSchemaAdapter;
import io.oryxos.tool.AuditedToolExecutor;
import io.oryxos.tool.DemoToolExecutor;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** Native Spring AI tool-calling contract, entirely offline with a mocked ChatModel. */
class SpringAiModelGatewayIntegrationTest {
  private static AgentProfile profile() {
    return new AgentProfile(
        "payment-real",
        "payment lab",
        Map.of("prompt", "Look up payments using the get_payment tool"),
        new AgentProfile.ProviderSettings("deepseek", "deepseek-chat", 0.1),
        List.of("get_payment"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        Map.of("max_iterations", 6));
  }

  private static ChatResponse calls(String... ids) {
    List<AssistantMessage.ToolCall> requests = new ArrayList<>();
    for (String id : ids) {
      requests.add(
          new AssistantMessage.ToolCall(
              "call-" + id, "function", "get_payment", "{\"payment_id\":\"" + id + "\"}"));
    }
    return new ChatResponse(
        List.of(
            new Generation(AssistantMessage.builder().content("").toolCalls(requests).build())));
  }

  private static ChatResponse answer(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  @Test
  void realSpringAiMessagesPreserveToolCallIdArgumentsAndResult() throws Exception {
    ChatModel model = mock(ChatModel.class);
    when(model.call(any(Prompt.class)))
        .thenReturn(calls("P1001"), answer("Payment P1001 is PENDING 120 AUD"));
    List<LlmCallEvent> llmAudit = new ArrayList<>();
    var provider =
        new ProviderService(Map.of("deepseek", model), new ToolSchemaAdapter(), llmAudit::add);
    var toolAudit = new ArrayList<io.oryxos.core.agent.ToolCallEvent>();
    var tools = new AuditedToolExecutor(new DemoToolExecutor(), toolAudit::add);
    var session = new AgentSession("real:student:payment-real", "payment-real");
    var loop =
        new ReActLoop(
            new SpringAiModelGateway(provider), tools, new PromptBuilder(Clock.systemUTC(), 20));

    String result = loop.run(session, "Look up P1001", profile());
    assertEquals("Payment P1001 is PENDING 120 AUD", result);
    assertEquals(1, toolAudit.size());
    assertTrue(toolAudit.getFirst().success());
    assertEquals(2, llmAudit.size());
    assertEquals(4, session.transcript().size());
    assertEquals(
        "{\"payment_id\":\"P1001\"}",
        session.transcript().get(1).toolCalls().getFirst().argumentsJson());

    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(model, times(2)).call(captor.capture());
    Prompt first = captor.getAllValues().getFirst();
    var options = (ToolCallingChatOptions) first.getOptions();
    assertFalse(options.getInternalToolExecutionEnabled());
    assertEquals("get_payment", options.getToolCallbacks().getFirst().getToolDefinition().name());
    Prompt second = captor.getAllValues().get(1);
    var nativeAssistant = (AssistantMessage) second.getInstructions().get(2);
    assertEquals("call-P1001", nativeAssistant.getToolCalls().getFirst().id());
    var nativeTool = (ToolResponseMessage) second.getInstructions().get(3);
    assertEquals("call-P1001", nativeTool.getResponses().getFirst().id());
    assertTrue(nativeTool.getResponses().getFirst().responseData().contains("PENDING 120 AUD"));

    // JSON round-trip guarantees the native call/reply metadata survives a restart.
    ObjectMapper mapper = new ObjectMapper();
    byte[] saved = mapper.writeValueAsBytes(session.transcript());
    List<AgentMessage> restored = mapper.readValue(saved, new TypeReference<>() {});
    assertEquals("call-P1001", restored.get(1).toolCalls().getFirst().id());
    assertEquals("call-P1001", restored.get(2).toolReplies().getFirst().id());
  }

  @Test
  void multipleToolCallsAreSentAsOneAssistantAndOneToolMessage() {
    ChatModel model = mock(ChatModel.class);
    when(model.call(any(Prompt.class)))
        .thenReturn(calls("P1001", "P1002"), answer("Both payments found"));
    var provider = new ProviderService(Map.of("deepseek", model), new ToolSchemaAdapter(), e -> {});
    var audit = new ArrayList<io.oryxos.core.agent.ToolCallEvent>();
    var session = new AgentSession("real:batch:payment-real", "payment-real");
    var loop =
        new ReActLoop(
            new SpringAiModelGateway(provider),
            new AuditedToolExecutor(new DemoToolExecutor(), audit::add),
            new PromptBuilder(Clock.systemUTC(), 20));
    assertEquals("Both payments found", loop.run(session, "Compare two payments", profile()));
    assertEquals(2, audit.size());
    assertEquals(4, session.transcript().size());
    assertEquals(2, session.transcript().get(1).toolCalls().size());
    assertEquals(2, session.transcript().get(2).toolReplies().size());
    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(model, times(2)).call(captor.capture());
    var response = (ToolResponseMessage) captor.getAllValues().get(1).getInstructions().get(3);
    assertEquals(2, response.getResponses().size());
  }

  @Test
  void unlistedToolIsRejectedAndNeverGetsASecondLlmTurn() {
    ChatModel model = mock(ChatModel.class);
    var forbidden = new AssistantMessage.ToolCall("bad-1", "function", "shell", "{}");
    var fakeResponse =
        new ChatResponse(
            List.of(
                new Generation(
                    AssistantMessage.builder().content("").toolCalls(List.of(forbidden)).build())));
    when(model.call(any(Prompt.class))).thenReturn(fakeResponse);
    var provider = new ProviderService(Map.of("deepseek", model), new ToolSchemaAdapter(), e -> {});
    var audit = new ArrayList<io.oryxos.core.agent.ToolCallEvent>();
    var session = new AgentSession("real:deny:payment-real", "payment-real");
    var loop =
        new ReActLoop(
            new SpringAiModelGateway(provider),
            new AuditedToolExecutor(new DemoToolExecutor(), audit::add),
            new PromptBuilder(Clock.systemUTC(), 20));
    assertThrows(
        SecurityException.class, () -> loop.run(session, "Do something unsafe", profile()));
    verify(model, times(1)).call(any(Prompt.class));
    assertEquals(1, audit.size());
    assertFalse(audit.getFirst().success());
    assertTrue(session.transcript().getLast().content().contains("SecurityException"));
  }

  @Test
  void oldTwoFieldSessionJsonStillLoads() throws Exception {
    var mapper = new ObjectMapper();
    var restored = mapper.readValue("{\"role\":\"USER\",\"content\":\"old\"}", AgentMessage.class);
    assertEquals("old", restored.content());
    assertTrue(restored.toolCalls().isEmpty());
    assertTrue(restored.toolReplies().isEmpty());
  }
}
