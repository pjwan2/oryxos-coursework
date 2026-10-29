package io.oryxos.provider;

import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.tool.ToolSpec;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Explicit named routing; exactly one LLM call; absolutely no tool execution. */
@Service
public class ProviderService {
  private final Map<String, ChatModel> models;
  private final ToolSchemaAdapter adapter;
  private final LlmCallAudit audit;

  public ProviderService(
      @Qualifier("oryxosModels") Map<String, ChatModel> models,
      ToolSchemaAdapter adapter,
      LlmCallAudit audit) {
    this.models = Map.copyOf(models);
    this.adapter = adapter;
    this.audit = audit;
  }

  public ChatResponse chat(String sessionId, AgentProfile profile, Prompt prompt) {
    return chat(sessionId, profile, prompt, List.of());
  }

  public ChatResponse chat(
      String sessionId, AgentProfile profile, Prompt prompt, List<ToolSpec> tools) {
    Objects.requireNonNull(profile, "profile is required");
    Objects.requireNonNull(prompt, "prompt is required");
    String providerName = profile.provider().name();
    ChatModel selected = models.get(providerName);
    if (selected == null) {
      throw new ProviderNotFoundException(providerName);
    }
    var options =
        ToolCallingChatOptions.builder()
            .model(profile.provider().model())
            .temperature(profile.provider().temperature())
            .toolCallbacks(adapter.toSpringAiTools(tools))
            .internalToolExecutionEnabled(false)
            .build();
    Prompt outbound = new Prompt(prompt.getInstructions(), options);
    long begin = System.nanoTime();
    ChatResponse response;
    try {
      response = Objects.requireNonNull(selected.call(outbound), "model returned null");
    } catch (RuntimeException e) {
      // Provider error messages can contain secrets: persist only a safe error category.
      audit.record(
          new LlmCallEvent(
              sessionId,
              providerName,
              profile.provider().model(),
              0,
              0,
              elapsed(begin),
              false,
              e.getClass().getSimpleName(),
              Instant.now()));
      throw ModelFailures.classify(providerName, e);
    }
    int input = 0;
    int output = 0;
    if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
      var usage = response.getMetadata().getUsage();
      input = usage.getPromptTokens();
      output = usage.getCompletionTokens();
    }
    audit.record(
        new LlmCallEvent(
            sessionId,
            providerName,
            profile.provider().model(),
            input,
            output,
            elapsed(begin),
            true,
            null,
            Instant.now()));
    return response;
  }

  private static long elapsed(long start) {
    return (System.nanoTime() - start) / 1_000_000L;
  }

  public boolean hasProvider(String name) {
    return models.containsKey(name);
  }
}
