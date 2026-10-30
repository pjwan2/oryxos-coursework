package io.oryxos;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.profile.ProfileValidator;
import io.oryxos.core.schedule.ScheduledTaskStore;
import io.oryxos.provider.ProfileRegistry;
import io.oryxos.scheduler.AgentScheduler;
import io.oryxos.scheduler.ScheduleProfileValidator;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Wires the scheduler to the same AgentService that serves HTTP and CLI chats. Schedules are
 * validated every time the application starts, even with {@code oryxos.scheduler.enabled=false};
 * that flag only decides whether triggers are registered.
 */
@Configuration
public class SchedulerConfiguration {
  private static final int MAX_POOL_SIZE = 32;

  @Bean
  public ThreadPoolTaskScheduler oryxosTaskScheduler(
      @Value("${oryxos.scheduler.pool-size:4}") int poolSize) {
    if (poolSize < 1 || poolSize > MAX_POOL_SIZE) {
      throw new IllegalArgumentException(
          "oryxos.scheduler.pool-size must be between 1 and " + MAX_POOL_SIZE);
    }
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(poolSize);
    scheduler.setThreadNamePrefix("oryxos-scheduler-");
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(30);
    return scheduler;
  }

  /** Agents are only registered with schedules the scheduler would accept. */
  @Bean
  public ProfileValidator scheduleProfileValidator() {
    return new ScheduleProfileValidator();
  }

  @Bean
  public AgentScheduler agentScheduler(
      @Qualifier("springAiAgentService") AgentService agent,
      ProfileRegistry registry,
      ScheduledTaskStore store,
      ThreadPoolTaskScheduler oryxosTaskScheduler,
      @Value("${oryxos.scheduler.enabled:true}") boolean enabled) {
    return new AgentScheduler(
        agent,
        () -> registry.all().values(),
        store,
        oryxosTaskScheduler,
        Clock.systemUTC(),
        enabled);
  }
}
