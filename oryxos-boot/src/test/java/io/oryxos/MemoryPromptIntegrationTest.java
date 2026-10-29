package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.memory.MarkdownMemoryStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Backend 1: the default Markdown file. */
@SpringBootTest(properties = "oryxos.profile-directory=../.oryxos/profiles")
class MemoryPromptIntegrationTest extends AbstractMemoryPromptIntegrationTest {
  private static final Path DIR = workDir();

  @DynamicPropertySource
  static void backend(DynamicPropertyRegistry registry) {
    registry.add("oryxos.memory.backend", () -> "markdown");
    registry.add("oryxos.memory.markdown-path", () -> DIR.resolve("MEMORY.md").toString());
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + DIR.resolve("app.db").toString().replace('\\', '/'));
  }

  @Test
  void theConfiguredBackendIsMarkdownAndTheFileHoldsBothSections() throws IOException {
    assertInstanceOf(MarkdownMemoryStore.class, store);
    agent.process(
        "web",
        "md-user",
        "memory-demo",
        "tool:save_memory {\"content\":\"md core\",\"scope\":\"CORE\"}");
    agent.process("web", "md-user", "memory-demo", "tool:save_memory {\"content\":\"md archive\"}");
    String raw = Files.readString(DIR.resolve("MEMORY.md"), StandardCharsets.UTF_8);
    assertTrue(raw.indexOf("md core") < raw.indexOf("## 归档记忆"));
    assertTrue(raw.indexOf("## 归档记忆") < raw.indexOf("md archive"));
  }
}
