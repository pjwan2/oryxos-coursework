package io.oryxos;

import io.oryxos.core.agent.ContextLoader;
import io.oryxos.core.memory.LongTermMemoryStore;
import io.oryxos.core.sandbox.Sandbox;
import io.oryxos.memory.BootstrapContextLoader;
import io.oryxos.memory.MarkdownMemoryStore;
import io.oryxos.memory.Mem0MemoryStore;
import io.oryxos.memory.MemoryContextLoader;
import io.oryxos.memory.MemoryService;
import io.oryxos.memory.RecallMemoryTool;
import io.oryxos.memory.SaveMemoryTool;
import io.oryxos.memory.SqliteMemoryStore;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the long-term memory backend from {@code oryxos.memory.backend} (markdown | sqlite |
 * mem0, default markdown). Everything above the {@link LongTermMemoryStore} bean is identical for
 * every backend. The save/recall tools are exposed as OryxTool beans, so ToolConfiguration picks
 * them up; an agent can still only call them if its Profile lists them.
 */
@Configuration
@EnableConfigurationProperties(MemoryConfiguration.MemoryProperties.class)
public class MemoryConfiguration {

  @ConfigurationProperties(prefix = "oryxos.memory")
  public static class MemoryProperties {
    private String backend = "markdown";
    private String markdownPath = ".oryxos/memory/MEMORY.md";
    private int archivalMaxEntries = 100;
    private int archivalMaxChars = 4000;
    private String mem0BaseUrl = "";
    private String mem0UserId = "oryxos";
    private String mem0ApiKey = "";

    public String getBackend() {
      return backend;
    }

    public void setBackend(String backend) {
      this.backend = backend;
    }

    public String getMarkdownPath() {
      return markdownPath;
    }

    public void setMarkdownPath(String markdownPath) {
      this.markdownPath = markdownPath;
    }

    public int getArchivalMaxEntries() {
      return archivalMaxEntries;
    }

    public void setArchivalMaxEntries(int archivalMaxEntries) {
      this.archivalMaxEntries = archivalMaxEntries;
    }

    public int getArchivalMaxChars() {
      return archivalMaxChars;
    }

    public void setArchivalMaxChars(int archivalMaxChars) {
      this.archivalMaxChars = archivalMaxChars;
    }

    public String getMem0BaseUrl() {
      return mem0BaseUrl;
    }

    public void setMem0BaseUrl(String mem0BaseUrl) {
      this.mem0BaseUrl = mem0BaseUrl;
    }

    public String getMem0UserId() {
      return mem0UserId;
    }

    public void setMem0UserId(String mem0UserId) {
      this.mem0UserId = mem0UserId;
    }

    public String getMem0ApiKey() {
      return mem0ApiKey;
    }

    public void setMem0ApiKey(String mem0ApiKey) {
      this.mem0ApiKey = mem0ApiKey;
    }
  }

  @Bean
  public LongTermMemoryStore longTermMemoryStore(MemoryProperties props, DataSource dataSource) {
    Clock clock = Clock.systemDefaultZone();
    String backend =
        props.getBackend() == null ? "" : props.getBackend().strip().toLowerCase(Locale.ROOT);
    return switch (backend) {
      case "markdown" -> new MarkdownMemoryStore(Path.of(props.getMarkdownPath()), clock);
      case "sqlite" -> new SqliteMemoryStore(dataSource, clock);
      case "mem0" ->
          new Mem0MemoryStore(
              HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
              props.getMem0BaseUrl(),
              props.getMem0UserId(),
              props.getMem0ApiKey(),
              clock);
      default ->
          throw new IllegalArgumentException(
              "oryxos.memory.backend must be markdown, sqlite or mem0 but was '"
                  + props.getBackend()
                  + "'");
    };
  }

  @Bean
  public MemoryService memoryService(LongTermMemoryStore store, MemoryProperties props) {
    return new MemoryService(
        store,
        new MemoryService.Limits(props.getArchivalMaxEntries(), props.getArchivalMaxChars()));
  }

  @Bean
  public SaveMemoryTool saveMemoryTool(MemoryService memory) {
    return new SaveMemoryTool(memory);
  }

  @Bean
  public RecallMemoryTool recallMemoryTool(MemoryService memory) {
    return new RecallMemoryTool(memory);
  }

  @Bean
  public ContextLoader bootstrapContextLoader(Sandbox sandbox) {
    return new BootstrapContextLoader(sandbox);
  }

  @Bean
  public ContextLoader memoryContextLoader(MemoryService memory) {
    return new MemoryContextLoader(memory);
  }
}
