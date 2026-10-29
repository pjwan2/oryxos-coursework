package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.agent.ModelTimeoutException;
import io.oryxos.core.agent.ModelUnavailableException;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.tool.ToolSpec;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.web.client.ResourceAccessException;

class ProviderServiceTest {
  private final ChatResponse response = mock(ChatResponse.class);
  private final List<LlmCallEvent> audited = new ArrayList<>();

  @Test
  void providerNamesRouteExactlyAndToolExecutionDisabled() {
    ChatModel deepseek = mock(ChatModel.class);
    ChatModel kimi = mock(ChatModel.class);
    when(kimi.call(any(Prompt.class))).thenReturn(response);
    ProviderService service =
        new ProviderService(
            Map.of("deepseek", deepseek, "kimi", kimi), new ToolSchemaAdapter(), audited::add);
    service.chat(
        "session-1",
        AgentProfile.example("kimi", "kimi-model"),
        new Prompt("hello"),
        List.of(new ToolSpec("http_get", "HTTP GET", "{\"type\":\"object\"}")));
    verify(kimi, times(1)).call(any(Prompt.class));
    verify(deepseek, never()).call(any(Prompt.class));
    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(kimi).call(captor.capture());
    ToolCallingChatOptions options = (ToolCallingChatOptions) captor.getValue().getOptions();
    assertFalse(options.getInternalToolExecutionEnabled());
    assertEquals("http_get", options.getToolCallbacks().getFirst().getToolDefinition().name());
    assertEquals(1, audited.size());
    assertTrue(audited.getFirst().success());
  }

  @Test
  void failureIsAuditedAndRethrown() {
    ChatModel bad = mock(ChatModel.class);
    when(bad.call(any(Prompt.class))).thenThrow(new RuntimeException("secret-token"));
    ProviderService service =
        new ProviderService(Map.of("bad", bad), new ToolSchemaAdapter(), audited::add);
    assertThrows(
        RuntimeException.class,
        () ->
            service.chat(
                "session-2", AgentProfile.example("bad", "bad-model"), new Prompt("hello")));
    assertFalse(audited.getFirst().success());
    assertFalse(audited.getFirst().errorMessage().contains("secret-token"));
  }

  @Test
  void aSlowProviderBecomesATimeoutAndAnUnreachableOneUnavailableBothStillAudited() {
    ChatModel slow = mock(ChatModel.class);
    when(slow.call(any(Prompt.class)))
        .thenThrow(new ResourceAccessException("io", new SocketTimeoutException("secret-token")));
    ChatModel down = mock(ChatModel.class);
    when(down.call(any(Prompt.class)))
        .thenThrow(new ResourceAccessException("io", new ConnectException("secret-token")));
    ProviderService service =
        new ProviderService(
            Map.of("slow", slow, "down", down), new ToolSchemaAdapter(), audited::add);

    ModelTimeoutException timeout =
        assertThrows(
            ModelTimeoutException.class,
            () -> service.chat("s", AgentProfile.example("slow", "m"), new Prompt("hi")));
    ModelUnavailableException unavailable =
        assertThrows(
            ModelUnavailableException.class,
            () -> service.chat("s", AgentProfile.example("down", "m"), new Prompt("hi")));

    assertFalse(timeout.getMessage().contains("secret-token"));
    assertFalse(unavailable.getMessage().contains("secret-token"));
    assertEquals(2, audited.size());
    assertTrue(audited.stream().noneMatch(LlmCallEvent::success));
    assertTrue(audited.stream().noneMatch(e -> e.errorMessage().contains("secret-token")));
  }

  @Test
  void missingProviderCannotSilentlyFallback() {
    ProviderService service =
        new ProviderService(
            Map.of("mock", mock(ChatModel.class)), new ToolSchemaAdapter(), audited::add);
    assertThrows(
        ProviderNotFoundException.class,
        () -> service.chat("session-3", AgentProfile.example("nope", "x"), new Prompt("hi")));
    assertTrue(audited.isEmpty());
  }
}
