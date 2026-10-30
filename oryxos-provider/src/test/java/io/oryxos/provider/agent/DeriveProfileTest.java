package io.oryxos.provider.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.profile.AgentProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One Profile schema: AGENT.md and profiles/*.yaml with the same fields give the same Profile. */
class DeriveProfileTest {
  @TempDir Path temp;

  @Test
  void anAgentMdAndALegacyYamlWithTheSameFieldsDeriveTheSameProfile() throws IOException {
    Path profiles = temp.resolve("profiles");
    Files.createDirectories(profiles);
    Files.writeString(
        profiles.resolve("daily-reconcile.yaml"),
        """
        name: daily-reconcile
        description: Reconciles the day's payments
        identity:
          prompt: "# Daily reconcile\\n\\nCompare the ledger with the bank file and report differences."
        provider: {name: mock, model: m1, temperature: 0.3}
        tools: [read_file, http_get]
        notify_channels:
          - {type: webhook, name: ops}
        schedules:
          - {id: nightly, cron: "0 0 2 * * *", zone: UTC, message: "Reconcile yesterday"}
        settings:
          max_iterations: 4
        """);
    AgentProfile legacy = AgentFixtures.profiles(profiles).loadAll().get("daily-reconcile");

    Path dir = AgentFixtures.write(temp.resolve("agents"), "daily-reconcile", AgentFixtures.DAILY);
    AgentProfile derived = AgentFixtures.loader(profiles).deriveProfile(dir);

    assertEquals(legacy, derived);
  }

  @Test
  void theMarkdownBodyBecomesTheSystemPromptAndTheFrontmatterDoesNot() {
    Path dir = AgentFixtures.write(temp.resolve("agents"), "daily-reconcile", AgentFixtures.DAILY);

    AgentProfile profile = AgentFixtures.loader(temp.resolve("profiles")).deriveProfile(dir);

    String prompt = String.valueOf(profile.identity().get("prompt"));
    assertEquals(
        "# Daily reconcile\n\nCompare the ledger with the bank file and report differences.",
        prompt);
  }

  @Test
  void skillsAndMcpServersAndBootstrapSurviveTheDerivation() {
    Path dir =
        AgentFixtures.write(
            temp.resolve("agents"),
            "rich",
            """
            ---
            description: d
            provider: mock
            model: m
            skills: [report-format]
            mcp_servers: [files]
            bootstrap: {text: "Always be brief"}
            ---
            Body
            """);

    AgentProfile profile = AgentFixtures.loader(temp.resolve("profiles")).deriveProfile(dir);

    assertEquals("rich", profile.name(), "the name defaults to the directory name");
    assertEquals(List.of("report-format"), profile.skills());
    assertEquals(List.of("files"), profile.mcpServers());
    assertEquals(Map.of("text", "Always be brief"), profile.bootstrap());
    assertTrue(profile.tools().isEmpty());
  }
}
