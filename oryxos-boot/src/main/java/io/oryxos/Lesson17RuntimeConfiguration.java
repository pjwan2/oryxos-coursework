package io.oryxos;

import io.oryxos.cli.PaymentScriptedGateway;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.AgentSessionStore;
import io.oryxos.core.agent.AgentToolExecutor;
import io.oryxos.core.agent.ModelGateway;
import io.oryxos.core.agent.PromptBuilder;
import io.oryxos.core.agent.ReActLoop;
import io.oryxos.core.agent.ToolCallAudit;
import io.oryxos.provider.ProfileRegistry;
import io.oryxos.tool.AuditedToolExecutor;
import io.oryxos.tool.DemoToolExecutor;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Offline lesson 17 app wiring. Do not mistake this mock for the production provider bridge. */
@Configuration
public class Lesson17RuntimeConfiguration {
  @Bean
  public ModelGateway lesson17ModelGateway() {
    return new PaymentScriptedGateway();
  }

  @Bean
  public AgentToolExecutor lesson17Tools(ToolCallAudit audit) {
    return new AuditedToolExecutor(new DemoToolExecutor(), audit);
  }

  @Bean
  public ReActLoop lesson17Loop(ModelGateway gateway, AgentToolExecutor tools) {
    return new ReActLoop(gateway, tools, new PromptBuilder(Clock.systemUTC(), 20));
  }

  @Bean
  public AgentService lesson17AgentService(
      ReActLoop loop, ProfileRegistry registry, AgentSessionStore store) {
    return new AgentService(loop, registry, store);
  }
}
