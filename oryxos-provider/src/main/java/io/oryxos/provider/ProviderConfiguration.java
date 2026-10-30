package io.oryxos.provider;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.TransientAiException;
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
  public ProfileRegistry profileRegistry(
      OryxOsProviderProperties props,
      @org.springframework.beans.factory.annotation.Qualifier("oryxosModels")
          Map<String, ChatModel> models) {
    ProfileLoader loader = new ProfileLoader(Path.of(props.getProfileDirectory()), models.keySet());
    return new ProfileRegistry(loader.loadAll());
  }
}
