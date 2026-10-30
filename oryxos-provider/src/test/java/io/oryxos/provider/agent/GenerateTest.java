package io.oryxos.provider.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.AgentMessage;
import io.oryxos.core.agent.ModelGateway;
import io.oryxos.core.agent.ModelTurn;
import io.oryxos.core.agent.ProfileNotFoundException;
import io.oryxos.core.profile.AgentConflictException;
import io.oryxos.core.profile.AgentDefinitionException;
import io.oryxos.core.profile.AgentDraft;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.profile.DraftUnavailableException;
import io.oryxos.provider.ProfileRegistry;
import io.oryxos.provider.agent.AgentFixtures.RecordingSync;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The model only proposes: description -> provider -> draft files -> validation -> a person
 * confirms. Nothing here writes a file, registers an agent or runs what the model produced.
 */
class GenerateTest {
  private static final String GOOD =
      """
      <<<FILE AGENT.md>>>
      ---
      name: helper
      description: Reconciles payments
      provider: {name: mock, model: mock-echo, temperature: 0.2}
      tools: [read_file]
      ---
      # Helper

      Compare the ledger with the bank file.
      <<<END>>>
      <<<FILE REFERENCE.md>>>
      Ledger columns: id, amount.
      <<<END>>>
      """;

  @TempDir Path temp;

  private Path root;
  private ProfileRegistry registry;
  private AgentLifecycleService lifecycle;
  private final List<String> sessionIds = new ArrayList<>();
  private final List<List<AgentMessage>> prompts = new ArrayList<>();
  private String modelAnswer = GOOD;
  private AgentDraftService drafts;

  @BeforeEach
  void setUp() throws IOException {
    Path profiles = Files.createDirectories(temp.resolve("profiles"));
    root = temp.resolve("agents");
    AgentLoader loader = AgentFixtures.loader(profiles);
    AgentDirectoryScanner scanner = new AgentDirectoryScanner(root, loader);
    AgentStartup.Report startup = AgentStartup.load(AgentFixtures.profiles(profiles), scanner);
    registry = new ProfileRegistry(startup.profiles());
    lifecycle = new AgentLifecycleService(registry, scanner, loader, new RecordingSync(), startup);
    ModelGateway model =
        (sessionId, profile, prompt, tools) -> {
          sessionIds.add(sessionId);
          prompts.add(prompt);
          return ModelTurn.finalAnswer(modelAnswer);
        };
    drafts = drafts(model, Set.of("mock"));
    lifecycle.create("helper", "placeholder");
  }

  private AgentDraftService drafts(ModelGateway model, Set<String> providers) {
    return new AgentDraftService(
        lifecycle, registry, model, () -> providers, () -> AgentFixtures.TOOLS);
  }

  private static List<String> treeOf(Path dir) throws IOException {
    try (Stream<Path> walk = Files.walk(dir)) {
      return walk.map(p -> dir.relativize(p).toString()).sorted().toList();
    }
  }

  @Test
  void aGoodAnswerBecomesAValidDraftOfSeveralFiles() {
    AgentDraft draft = drafts.generate("helper", "Reconcile yesterday's payments");

    assertTrue(draft.valid(), draft.problems().toString());
    assertEquals(List.of(), draft.problems());
    assertEquals(
        List.of("AGENT.md", "REFERENCE.md"), draft.files().stream().map(f -> f.path()).toList());
    assertTrue(draft.files().getFirst().content().contains("Compare the ledger"));
  }

  @Test
  void generatingNeverWritesAFileOrChangesTheRegisteredAgent() throws IOException {
    List<String> before = treeOf(root);
    String descriptionBefore = registry.get("helper").description();

    drafts.generate("helper", "Reconcile yesterday's payments");

    assertEquals(before, treeOf(root), "no file was created or changed");
    assertEquals(descriptionBefore, registry.get("helper").description());
    assertEquals(List.of("helper"), lifecycle.agentNames());
  }

  @Test
  void theCallGoesThroughTheAuditedGatewayUnderAGeneratorSessionAndOffersNoTools() {
    drafts.generate("helper", "Reconcile");

    assertEquals(List.of("admin:generator:helper"), sessionIds);
    List<AgentMessage> prompt = prompts.getFirst();
    assertEquals(AgentMessage.Role.SYSTEM, prompt.getFirst().role());
    assertTrue(prompt.getLast().content().contains("Reconcile"));
  }

  @Test
  void invalidYamlComesBackAsAReadableProblemAndTheDraftIsStillShown() {
    modelAnswer = "<<<FILE AGENT.md>>>\n---\ndescription: [broken\n---\nBody\n<<<END>>>";

    AgentDraft draft = drafts.generate("helper", "x");

    assertFalse(draft.valid());
    assertEquals(1, draft.files().size(), "the person can fix the text");
    assertTrue(draft.problems().getFirst().contains("not valid YAML"), draft.problems().toString());
    assertFalse(draft.problems().getFirst().contains("\tat "), "no stack trace");
  }

  @Test
  void aDraftWithAnUnknownToolOrFieldIsReportedNotSaved() {
    modelAnswer =
        "<<<FILE AGENT.md>>>\n---\ndescription: d\nprovider: {name: mock, model: m}\n"
            + "tools: [launch_missiles]\n---\nBody\n<<<END>>>";

    AgentDraft draft = drafts.generate("helper", "x");

    assertFalse(draft.valid());
    assertTrue(draft.problems().getFirst().contains("tool 'launch_missiles' is not registered"));
  }

  @Test
  void aBareAgentMdOrACodeFencedOneIsUnderstoodToo() {
    modelAnswer =
        "```markdown\n---\ndescription: d\nprovider: {name: mock, model: m}\n---\nBody\n```";

    AgentDraft draft = drafts.generate("helper", "x");

    assertTrue(draft.valid(), draft.problems().toString());
    assertEquals("AGENT.md", draft.files().getFirst().path());
  }

  @Test
  void anAnswerWithoutAnAgentMdSaysSo() {
    modelAnswer = "I am sorry, I cannot help with that.";

    AgentDraft draft = drafts.generate("helper", "x");

    assertFalse(draft.valid());
    assertTrue(draft.files().isEmpty());
    assertTrue(draft.problems().getFirst().contains("did not return an AGENT.md"));
    assertFalse(
        String.join(" ", draft.problems()).contains("sorry"),
        "the raw provider text is not echoed back");
  }

  @Test
  void scriptsUnsafeNamesAndExtraFilesAreLeftOutOfTheDraft() {
    modelAnswer =
        GOOD
            + "<<<FILE scripts/run.py>>>\nprint('x')\n<<<END>>>\n"
            + "<<<FILE ../escape.md>>>\nx\n<<<END>>>\n"
            + "<<<FILE skills/a.md>>>\na\n<<<END>>>\n"
            + "<<<FILE skills/b.md>>>\nb\n<<<END>>>\n"
            + "<<<FILE skills/c.md>>>\nc\n<<<END>>>\n"
            + "<<<FILE skills/d.md>>>\nd\n<<<END>>>\n";

    AgentDraft draft = drafts.generate("helper", "x");

    List<String> paths = draft.files().stream().map(f -> f.path()).toList();
    assertFalse(paths.contains("scripts/run.py"), "scripts are never drafted");
    assertFalse(paths.stream().anyMatch(p -> p.contains("..")));
    assertTrue(paths.size() <= AgentDraftService.MAX_DRAFT_FILES);
    assertFalse(draft.valid(), "the problems are reported: " + draft.problems());
    assertTrue(draft.problems().stream().anyMatch(p -> p.contains("Only Markdown files")));
  }

  @Test
  void aProviderThatIsNotConfiguredIsAClearErrorNotADraft() {
    AgentDraftService unconfigured =
        drafts(
            (sessionId, profile, prompt, tools) -> ModelTurn.finalAnswer("never called"),
            Set.of("deepseek"));

    DraftUnavailableException refused =
        assertThrows(DraftUnavailableException.class, () -> unconfigured.generate("helper", "x"));

    assertTrue(refused.getMessage().contains("No usable provider/model"), refused.getMessage());
    assertTrue(refused.getMessage().contains("'mock'"), refused.getMessage());
    assertTrue(sessionIds.isEmpty(), "the model was not called");
  }

  @Test
  void descriptionAndTargetAreChecked() {
    assertThrows(AgentDefinitionException.class, () -> drafts.generate("helper", " "));
    assertThrows(
        AgentDefinitionException.class,
        () -> drafts.generate("helper", "x".repeat(AgentDraftService.MAX_DESCRIPTION + 1)));
    assertThrows(ProfileNotFoundException.class, () -> drafts.generate("nobody", "x"));

    ProfileRegistry withLegacy = new ProfileRegistry(java.util.Map.of("old", legacyProfile()));
    AgentDraftService forLegacy =
        new AgentDraftService(
            lifecycle,
            withLegacy,
            (s, p, m, t) -> ModelTurn.finalAnswer(""),
            () -> Set.of("mock"),
            () -> Set.of());
    assertThrows(AgentConflictException.class, () -> forLegacy.generate("old", "x"));
    assertTrue(sessionIds.isEmpty());
  }

  private static AgentProfile legacyProfile() {
    return AgentProfile.example("mock", "m");
  }

  @Test
  void theSystemPromptNamesTheAllowedToolsButNeverLeaksIntoTheDraft() {
    AgentDraft draft = drafts.generate("helper", "x");

    String system = prompts.getFirst().getFirst().content();
    assertTrue(system.contains("read_file"));
    for (var file : draft.files()) {
      assertFalse(file.content().contains("Allowed tools"), "the prompt is not part of the draft");
    }
  }
}
