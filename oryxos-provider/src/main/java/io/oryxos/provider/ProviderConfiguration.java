package io.oryxos.provider;

import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.profile.ProfileChangeListener;
import io.oryxos.core.profile.ProfileValidator;
import io.oryxos.core.schedule.ProfileScheduleSync;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.provider.agent.AgentDirectoryScanner;
import io.oryxos.provider.agent.AgentDraftService;
import io.oryxos.provider.agent.AgentLifecycleService;
import io.oryxos.provider.agent.AgentLoader;
import io.oryxos.provider.agent.AgentStartup;
import io.oryxos.provider.agent.AgentWorkspace;
import io.oryxos.provider.agent.WorkspaceWatcher;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

/** Builds explicit provider-name-to-ChatModel mapping without starter auto-creation. */
@Configuration
@EnableConfigurationProperties(OryxOsProviderProperties.class)
public class ProviderConfiguration {
  private static final int MAX_ATTEMPTS = 3;
  private static final long RETRY_PAUSE_MS = 300;
  private static final long CONNECT_TIMEOUT_SECONDS = 5;
  private static final long READ_TIMEOUT_SECONDS = 120;

  @Bean("oryxosModels")
  public Map<String, ChatModel> oryxosModels(OryxOsProviderProperties props) {
    Map<String, ChatModel> result = new LinkedHashMap<>();
    for (var cfg : props.getProviders()) {
      if (cfg.getName() == null || cfg.getName().isBlank()) {
        throw new IllegalArgumentException("Each provider needs a name");
      }
      if ("mock".equals(cfg.getType())) {
        result.put(cfg.getName(), new MockChatModel());
      } else if ("openai-compatible".equals(cfg.getType())) {
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
          continue; // no key: do not register this provider, but keep app bootable
        }
        OpenAiApi api =
            OpenAiApi.builder()
                .baseUrl(cfg.getBaseUrl())
                .apiKey(cfg.getApiKey())
                .restClientBuilder(RestClient.builder().requestFactory(boundedRequestFactory()))
                .build();
        ChatModel model =
            OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(cfg.getModel()).build())
                .retryTemplate(boundedRetry())
                .build();
        result.put(cfg.getName(), model);
      } else {
        throw new IllegalArgumentException("Unsupported provider type for " + cfg.getName());
      }
    }
    if (!result.containsKey("mock")) {
      throw new IllegalStateException("A local mock provider is required for offline tests");
    }
    return Map.copyOf(result);
  }

  /**
   * Spring AI's default retry (10 attempts, exponential back-off that grows to minutes, and it also
   * retries a refused connection) would hold an HTTP request thread for minutes while a provider is
   * down, so the 503 that the REST layer promises would never arrive. Retry only when the provider
   * itself says "try again" (HTTP 429/5xx), three times at most, and fail at once when it cannot be
   * reached.
   */
  static RetryTemplate boundedRetry() {
    return RetryTemplate.builder()
        .maxAttempts(MAX_ATTEMPTS)
        .fixedBackoff(Duration.ofMillis(RETRY_PAUSE_MS))
        .retryOn(TransientAiException.class)
        .build();
  }

  /** A provider that accepts the connection but never answers must end in a timeout (504). */
  static JdkClientHttpRequestFactory boundedRequestFactory() {
    HttpClient http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS));
    return factory;
  }

  @Bean
  public AgentLoader agentLoader(
      OryxOsProviderProperties props,
      @Qualifier("oryxosModels") Map<String, ChatModel> models,
      ObjectProvider<ToolRegistry> tools,
      ObjectProvider<ProfileValidator> validators) {
    ProfileLoader profiles =
        new ProfileLoader(Path.of(props.getProfileDirectory()), models.keySet());
    return new AgentLoader(
        profiles,
        () -> {
          ToolRegistry registry = tools.getIfAvailable();
          return registry == null ? null : Set.copyOf(registry.names());
        },
        validators.orderedStream().toList());
  }

  @Bean
  public AgentDirectoryScanner agentDirectoryScanner(
      OryxOsProviderProperties props, AgentLoader loader) {
    return new AgentDirectoryScanner(Path.of(props.getAgentsDirectory()), loader);
  }

  /**
   * Legacy YAML profiles plus the AGENT.md directories, loaded once and merged without overrides.
   */
  @Bean
  public AgentStartup.Report agentStartupReport(
      OryxOsProviderProperties props,
      @Qualifier("oryxosModels") Map<String, ChatModel> models,
      AgentDirectoryScanner scanner) {
    ProfileLoader legacy = new ProfileLoader(Path.of(props.getProfileDirectory()), models.keySet());
    return AgentStartup.load(legacy, scanner);
  }

  @Bean
  public ProfileRegistry profileRegistry(
      AgentStartup.Report startup, ObjectProvider<ProfileChangeListener> listeners) {
    ProfileRegistry registry = new ProfileRegistry(startup.profiles());
    listeners.orderedStream().forEach(registry::addListener);
    return registry;
  }

  @Bean
  public AgentLifecycleService agentLifecycleService(
      OryxOsProviderProperties props,
      ProfileRegistry registry,
      AgentDirectoryScanner scanner,
      AgentLoader loader,
      ObjectProvider<ProfileScheduleSync> scheduler,
      AgentStartup.Report startup) {
    return new AgentLifecycleService(
        registry,
        scanner,
        loader,
        new LazyScheduleSync(scheduler),
        startup,
        new AgentLifecycleService.Options(
            Path.of(props.getArchiveDirectory()),
            props.getNewAgentProvider(),
            props.getNewAgentModel(),
            java.time.Clock.systemUTC()));
  }

  @Bean
  public AgentWorkspace agentWorkspace(AgentLifecycleService lifecycle) {
    return new AgentWorkspace(lifecycle);
  }

  /** The draft call goes through the same audited gateway as every other model call. */
  @Bean
  public AgentDraftService agentDraftService(
      AgentLifecycleService lifecycle,
      ProfileRegistry registry,
      SpringAiModelGateway gateway,
      @Qualifier("oryxosModels") Map<String, ChatModel> models,
      ObjectProvider<ToolRegistry> tools) {
    return new AgentDraftService(
        lifecycle,
        registry,
        gateway,
        models::keySet,
        () -> {
          ToolRegistry installed = tools.getIfAvailable();
          return installed == null ? Set.of() : Set.copyOf(installed.names());
        });
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  @ConditionalOnProperty(name = "oryxos.agents-watch", havingValue = "true", matchIfMissing = true)
  public WorkspaceWatcher workspaceWatcher(
      AgentLifecycleService lifecycle, OryxOsProviderProperties props) {
    return new WorkspaceWatcher(
        lifecycle,
        Duration.ofMillis(props.getAgentsWatchDebounceMillis()),
        Duration.ofSeconds(props.getAgentsReconcileSeconds()));
  }

  /** The scheduler bean is resolved on first use, so the registry never waits for it. */
  private static final class LazyScheduleSync implements ProfileScheduleSync {
    private final ObjectProvider<ProfileScheduleSync> target;

    LazyScheduleSync(ObjectProvider<ProfileScheduleSync> target) {
      this.target = target;
    }

    @Override
    public void registerProfile(AgentProfile profile) {
      ProfileScheduleSync sync = target.getIfAvailable();
      if (sync != null) {
        sync.registerProfile(profile);
      }
    }

    @Override
    public void unregisterProfile(String profileName) {
      ProfileScheduleSync sync = target.getIfAvailable();
      if (sync != null) {
        sync.unregisterProfile(profileName);
      }
    }
  }
}
