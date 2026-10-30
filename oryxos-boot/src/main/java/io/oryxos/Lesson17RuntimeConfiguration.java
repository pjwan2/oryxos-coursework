package io.oryxos;

import io.oryxos.cli.PaymentScriptedGateway;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.AgentSessionStore;
import io.oryxos.core.agent.AgentToolExecutor;
import io.oryxos.core.agent.ContextLoader;
import io.oryxos.core.agent.ModelGateway;
import io.oryxos.core.agent.PromptBuilder;
import io.oryxos.core.agent.ReActLoop;
import io.oryxos.core.agent.ToolCallAudit;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.provider.ProfileRegistry;
import io.oryxos.provider.SpringAiModelGateway;
import io.oryxos.tool.AuditedToolExecutor;
import io.oryxos.tool.RegistryToolExecutor;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Two independent application entry points share the same ToolExecutor and storage. The original
 * scripted Payment Lab stays the default for existing tests; /labs/spring-ai/payment uses a real
 * ChatModel via ProviderService.
 */
@Configuration
public class Lesson17RuntimeConfiguration {
  @Bean("lesson17ModelGateway")
  @Primary
  public ModelGateway scriptedModelGateway() {
    return new PaymentScriptedGateway();
  }

  @Bean
  public AgentToolExecutor lesson17Tools(
      ToolRegistry registry, ToolCallAudit audit, ProfileRegistry profiles) {
    // Each call is judged by the agent's CURRENT definition, so tightening a permission applies to
    // the very next tool call, even inside a turn that started before the change.
    return new AuditedToolExecutor(new RegistryToolExecutor(registry, profiles::get), audit);
  }

  /** One prompt builder for every loop; ContextLoaders (bootstrap, memory) are injected here. */
  @Bean
  public PromptBuilder promptBuilder(List<ContextLoader> contextLoaders) {
    return new PromptBuilder(Clock.systemUTC(), 20, contextLoaders);
  }

  @Bean("lesson17Loop")
  @Primary
  public ReActLoop scriptedLoop(
      @Qualifier("lesson17ModelGateway") ModelGateway gateway,
      AgentToolExecutor tools,
      PromptBuilder prompts) {
    return new ReActLoop(gateway, tools, prompts);
  }

  @Bean("springAiReActLoop")
  public ReActLoop springAiLoop(
      SpringAiModelGateway gateway, AgentToolExecutor tools, PromptBuilder prompts) {
    return new ReActLoop(gateway, tools, prompts);
  }

  @Bean("lesson17AgentService")
  @Primary
  public AgentService scriptedAgentService(
      @Qualifier("lesson17Loop") ReActLoop loop,
      ProfileRegistry registry,
      AgentSessionStore store) {
    return new AgentService(loop, registry, store);
  }

  @Bean("springAiAgentService")
  public AgentService springAiAgentService(
      @Qualifier("springAiReActLoop") ReActLoop loop,
      ProfileRegistry registry,
      AgentSessionStore store) {
    return new AgentService(loop, registry, store);
  }
}
