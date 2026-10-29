package io.oryxos.provider;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds explicit provider-name-to-ChatModel mapping without starter auto-creation. */
@Configuration
@EnableConfigurationProperties(OryxOsProviderProperties.class)
public class ProviderConfiguration {
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
            OpenAiApi.builder().baseUrl(cfg.getBaseUrl()).apiKey(cfg.getApiKey()).build();
        ChatModel model =
            OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(cfg.getModel()).build())
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

  @Bean
  public ProfileRegistry profileRegistry(
      OryxOsProviderProperties props,
      @org.springframework.beans.factory.annotation.Qualifier("oryxosModels")
          Map<String, ChatModel> models) {
    ProfileLoader loader = new ProfileLoader(Path.of(props.getProfileDirectory()), models.keySet());
    return new ProfileRegistry(loader.loadAll());
  }
}
