package io.oryxos.provider.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.AgentMessage;
import io.oryxos.core.agent.AgentSession;
import io.oryxos.core.agent.PromptBuilder;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.provider.ProfileRegistry;
import io.oryxos.provider.agent.AgentFixtures.RecordingSync;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Only the Markdown body is instruction text. Reference files, skills and scripts are named in the
 * prompt but their content is read, if at all, by the agent itself through the file tools.
 */
class ProgressiveDisclosureTest {
  private static final String BODY_V1 = "Reconcile the ledger. BODY-ONE";
  private static final String BODY_V2 = "Reconcile the ledger strictly. BODY-TWO";

  @TempDir Path temp;

  private Path root;
  private Path dir;
  private ProfileRegistry registry;
  private AgentLifecycleService service;
  private final PromptBuilder prompts =
      new PromptBuilder(Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC), 20);

  private static String agentMd(String body) {
    return "---\nname: recon\ndescription: SECRET-FRONTMATTER-DESCRIPTION\n"
        + "provider: {name: mock, model: m}\ntools: [read_file]\n"
        + "schedules:\n  - {id: nightly, cron: \"0 0 2 * * *\", zone: UTC, message: SCHEDULE-MESSAGE}\n"
        + "---\n"
        + body
        + "\n";
  }

  @BeforeEach
  void setUp() {
    Path profiles = temp.resolve("profiles");
    root = temp.resolve("agents");
    dir = AgentFixtures.write(root, "recon", agentMd(BODY_V1));
    AgentFixtures.file(dir, "REFERENCE.md", "REFERENCE-CONTENT-MARKER");
    AgentFixtures.file(dir, "skills/report-format.md", "SKILL-CONTENT-MARKER");
    AgentFixtures.file(dir, "scripts/reconcile.py", "print('SCRIPT-CONTENT-MARKER')");
    AgentLoader loader = AgentFixtures.loader(profiles);
    AgentDirectoryScanner scanner = new AgentDirectoryScanner(root, loader);
    AgentStartup.Report report = AgentStartup.load(AgentFixtures.profiles(profiles), scanner);
    registry = new ProfileRegistry(report.profiles());
    service = new AgentLifecycleService(registry, scanner, loader, new RecordingSync(), report);
  }

  private String systemPrompt() {
    AgentProfile profile = registry.get("recon");
    AgentSession session = new AgentSession("api:u:recon", "recon");
    List<AgentMessage> built = prompts.build(session, profile);
    assertEquals(AgentMessage.Role.SYSTEM, built.getFirst().role());
    return built.getFirst().content();
  }

  @Test
  void theBodyIsInThePromptAndTheFrontmatterIsNot() {
    String prompt = systemPrompt();

    assertTrue(prompt.contains(BODY_V1), prompt);
    assertFalse(
        prompt.contains("SECRET-FRONTMATTER-DESCRIPTION"), "frontmatter text is not repeated");
    assertFalse(prompt.contains("SCHEDULE-MESSAGE"));
    assertFalse(prompt.contains("provider:"));
    assertFalse(prompt.contains("---"));
  }

  @Test
  void resourcesAreListedByNameButTheirContentIsNeverLoaded() {
    String prompt = systemPrompt();

    assertTrue(prompt.contains("REFERENCE.md"), prompt);
    assertTrue(prompt.contains("skills/report-format.md"), prompt);
    assertTrue(prompt.contains("scripts/reconcile.py"), prompt);
    assertFalse(prompt.contains("REFERENCE-CONTENT-MARKER"));
    assertFalse(prompt.contains("SKILL-CONTENT-MARKER"));
    assertFalse(prompt.contains("SCRIPT-CONTENT-MARKER"));
  }

  @Test
  void anAgentWithoutResourcesGetsOnlyItsBody() throws IOException {
    Path plain =
        AgentFixtures.write(root, "plain", agentMd(BODY_V1).replace("name: recon", "name: plain"));
    service.register(plain);

    String prompt =
        prompts
            .build(new AgentSession("api:u:plain", "plain"), registry.get("plain"))
            .getFirst()
            .content();

    assertTrue(prompt.contains(BODY_V1));
    assertFalse(prompt.contains("Agent files"), "no file index when there are no files");
  }

  @Test
  void loadingNeverRunsAScript() throws IOException {
    Path marker = temp.resolve("script-ran.txt");
    AgentFixtures.file(
        dir, "scripts/touch.sh", "touch '" + marker.toString().replace('\\', '/') + "'\n");

    service.register(dir);
    service.reload("recon");

    assertFalse(Files.exists(marker), "scripts are data until a tool is allowed to run them");
    assertEquals(List.of("read_file"), registry.get("recon").tools(), "no shell tool was granted");
  }

  @Test
  void aChangedBodyIsUsedByTheNextPromptAndTheOldOneIsGone() {
    assertTrue(systemPrompt().contains("BODY-ONE"));

    AgentFixtures.write(root, "recon", agentMd(BODY_V2));
    service.reload("recon");

    String prompt = systemPrompt();
    assertTrue(prompt.contains("BODY-TWO"), prompt);
    assertFalse(prompt.contains("BODY-ONE"), "no stale instructions: " + prompt);
  }

  @Test
  void aNewResourceAppearsInThePromptAfterReloadAndARemovedOneDisappears() throws IOException {
    AgentFixtures.file(dir, "skills/extra.md", "EXTRA");
    Files.delete(dir.resolve("REFERENCE.md"));

    service.reload("recon");

    String prompt = systemPrompt();
    assertTrue(prompt.contains("skills/extra.md"));
    assertFalse(prompt.contains("REFERENCE.md"));
    assertFalse(prompt.contains("EXTRA"), "the new file is named, not read");
  }
}
