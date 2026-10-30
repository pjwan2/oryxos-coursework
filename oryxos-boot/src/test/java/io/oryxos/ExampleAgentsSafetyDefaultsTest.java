package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The examples open the loopback network so their notifications can reach a local receiver. That
 * must stay a visible demo-only choice: the application's own defaults deny private networks, no
 * shipped configuration turns them on, and every example that asks for it says so next to the line.
 */
class ExampleAgentsSafetyDefaultsTest {
  private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

  @Test
  void theOperatorCeilingDeniesPrivateNetworksAndHostsByDefault() {
    ToolConfiguration.SandboxProperties defaults = new ToolConfiguration.SandboxProperties();

    assertFalse(defaults.isAllowPrivateNetworks());
    assertEquals(List.of(), defaults.getHosts());
    assertEquals(List.of(), defaults.getCommands());
  }

  @Test
  void noShippedConfigurationEnablesPrivateNetworks() throws IOException {
    List<Path> shipped =
        List.of(
            REPO.resolve("oryxos-boot/src/main/resources/application.yaml"),
            REPO.resolve("config/env.example"));
    for (Path file : shipped) {
      String text = Files.readString(file);
      assertFalse(
          text.toLowerCase(java.util.Locale.ROOT).replace("_", "-").contains("private-networks"),
          file + " must not mention (let alone enable) private networks");
    }
  }

  @Test
  void everyExampleThatOpensPrivateNetworksMarksItAsLocalDemoOnly() throws IOException {
    int opened = 0;
    for (String name : List.of("weather-daily", "daily-tech-digest", "github-daily")) {
      List<String> lines =
          Files.readAllLines(DemoSupport.EXAMPLES.resolve(name).resolve("AGENT.md"));
      for (int i = 0; i < lines.size(); i++) {
        if (lines.get(i).trim().startsWith("allow_private_networks:")) {
          opened++;
          String context = String.join("\n", lines.subList(Math.max(0, i - 4), i));
          assertTrue(
              context.contains("LOCAL DEMO ONLY"),
              name + " opens private networks without a 'LOCAL DEMO ONLY' comment above it");
        }
      }
    }
    assertEquals(3, opened, "the three examples are the only ones that do");
  }
}
