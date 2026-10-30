package io.oryxos.provider.agent;

import static io.oryxos.provider.agent.AgentFixtures.DAILY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.oryxos.core.profile.AgentDefinitionException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentLoaderTest {
  @TempDir Path temp;

  private Path root;
  private AgentLoader loader;

  @BeforeEach
  void setUp() {
    root = temp.resolve("agents");
    loader = AgentFixtures.loader(temp.resolve("profiles"));
  }

  private String problemOf(String name, String agentMd) {
    Path dir = AgentFixtures.write(root, name, agentMd);
    return assertThrows(AgentDefinitionException.class, () -> loader.load(dir)).getMessage();
  }

  private static String agent(String frontmatter, String body) {
    return "---\n" + frontmatter + "\n---\n" + body + "\n";
  }

  @Test
  void aCompleteAgentLoadsWithEveryDeclaredField() {
    Path dir = AgentFixtures.write(root, "daily-reconcile", DAILY);

    AgentDefinition definition = loader.load(dir);

    assertEquals("daily-reconcile", definition.name());
    var profile = definition.profile();
    assertEquals("Reconciles the day's payments", profile.description());
    assertEquals("mock", profile.provider().name());
    assertEquals("m1", profile.provider().model());
    assertEquals(0.3, profile.provider().temperature());
    assertEquals(List.of("read_file", "http_get"), profile.tools());
    assertEquals(1, profile.notifyChannels().size());
    assertEquals("nightly", profile.schedules().getFirst().get("id"));
    assertEquals(4, profile.settings().get("max_iterations"));
    assertTrue(definition.instructions().startsWith("# Daily reconcile"));
    assertTrue(profile.identity().get("prompt").toString().contains("Compare the ledger"));
  }

  @Test
  void theShorthandProviderPlusModelIsTheSameAsTheNestedForm() {
    AgentDefinition flat =
        loader.parse(
            "flat",
            agent("description: d\nprovider: mock\nmodel: m1\ntemperature: 0.5", "Body"),
            root.resolve("flat"),
            List.of());
    AgentDefinition nested =
        loader.parse(
            "flat",
            agent("description: d\nprovider: {name: mock, model: m1, temperature: 0.5}", "Body"),
            root.resolve("flat"),
            List.of());

    assertEquals(nested.profile(), flat.profile());
  }

  @Test
  void aMissingAgentMdSaysSoAndNamesTheDirectory() {
    Path dir = root.resolve("empty");
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }

    String message =
        assertThrows(AgentDefinitionException.class, () -> loader.load(dir)).getMessage();

    assertTrue(message.contains("agents/empty/AGENT.md"), message);
    assertTrue(message.contains("not found"), message);
  }

  @Test
  void aFileWithoutFrontmatterIsRefusedWithAnExplanation() {
    String message = problemOf("plain", "# just markdown\n\nno frontmatter\n");
    assertTrue(message.contains("must start with"), message);
  }

  @Test
  void aFrontmatterThatIsNeverClosedIsRefused() {
    String message =
        problemOf("open", "---\nname: open\ndescription: d\n\nbody without a closing fence\n");
    assertTrue(message.contains("not closed"), message);
  }

  @Test
  void brokenYamlIsReportedAsYamlNotAsAStackTrace() {
    String message = problemOf("broken", agent("description: [unclosed\nprovider: mock", "Body"));
    assertTrue(message.contains("not valid YAML"), message);
    assertFalse(message.contains("\tat "), "no stack trace in the text");
  }

  @Test
  void duplicateKeysAreAnErrorNotALastOneWins() {
    String message =
        problemOf(
            "dup", agent("description: a\ndescription: b\nprovider: {name: mock, model: m}", "B"));
    assertTrue(message.contains("not valid YAML"), message);
  }

  @Test
  void requiredFieldsAreChecked() {
    assertTrue(
        problemOf("nodesc", agent("provider: {name: mock, model: m}", "Body"))
            .contains("description is required"));
    assertTrue(
        problemOf("noprov", agent("description: d", "Body")).contains("provider is required"));
    assertTrue(
        problemOf("nomodel", agent("description: d\nprovider: mock", "Body"))
            .contains("model is required"));
    assertTrue(
        problemOf("nobody", agent("description: d\nprovider: {name: mock, model: m}", ""))
            .contains("no instructions"));
  }

  @Test
  void anUnknownFieldIsNamedAndTheAllowedOnesAreListed() {
    String message =
        problemOf(
            "typo",
            agent("description: d\nprovider: {name: mock, model: m}\ntoools: [read_file]", "B"));
    assertTrue(message.contains("toools"), message);
    assertTrue(message.contains("allowed"), message);
    assertTrue(message.contains("tools"), message);
  }

  @Test
  void theNameMustBeTheDirectoryName() {
    String message =
        problemOf("one", agent("name: two\ndescription: d\nprovider: {name: mock, model: m}", "B"));
    assertTrue(message.contains("directory name 'one'"), message);
  }

  @Test
  void anUnsafeDirectoryNameIsRefusedBecauseItBecomesPartOfSessionIds() {
    String message =
        assertThrows(
                AgentDefinitionException.class,
                () ->
                    loader.parse(
                        "bad name",
                        agent("description: d\nprovider: {name: mock, model: m}", "B"),
                        root.resolve("bad name"),
                        List.of()))
            .getMessage();
    assertTrue(message.contains("[A-Za-z0-9_-]{1,64}"), message);
  }

  @Test
  void anUnknownProviderIsRefusedByTheSameValidatorAsProfiles() {
    String message =
        problemOf("noprov2", agent("description: d\nprovider: {name: nope, model: m}", "B"));
    assertTrue(message.contains("unknown provider: nope"), message);
  }

  @Test
  void aToolThatIsNotRegisteredIsNamedTogetherWithTheRegisteredOnes() {
    String message =
        problemOf(
            "badtool",
            agent(
                "description: d\nprovider: {name: mock, model: m}\ntools: [read_file, fly_to_moon]",
                "B"));
    assertTrue(message.contains("tool 'fly_to_moon' is not registered"), message);
    assertTrue(message.contains("http_get"), "the registered tools are listed: " + message);
  }

  @Test
  void aToolListedTwiceIsRefused() {
    String message =
        problemOf(
            "twice",
            agent(
                "description: d\nprovider: {name: mock, model: m}\ntools: [read_file, read_file]",
                "B"));
    assertTrue(message.contains("listed twice"), message);
  }

  @Test
  void aWrongTypeNamesTheField() {
    assertTrue(
        problemOf(
                "t1",
                agent("description: d\nprovider: {name: mock, model: m}\ntools: read_file", "B"))
            .contains("tools must be a list"));
    assertTrue(
        problemOf(
                "t2", agent("description: d\nprovider: {name: mock, model: m}\nsettings: [1]", "B"))
            .contains("settings must be a mapping"));
    assertTrue(
        problemOf(
                "t3",
                agent("description: d\nprovider: {name: mock, model: m}\nschedules: [x]", "B"))
            .contains("schedules must contain only mappings"));
  }

  @Test
  void temperatureOutOfRangeIsRefused() {
    String message =
        problemOf(
            "hot", agent("description: d\nprovider: {name: mock, model: m, temperature: 9}", "B"));
    assertTrue(message.contains("temperature"), message);
  }

  @Test
  void anInvalidSandboxDeclarationIsReportedWithItsPath() {
    String message =
        problemOf(
            "sbx",
            agent(
                "description: d\nprovider: {name: mock, model: m}\nsettings:\n  sandbox: {read_roots: ['/']}",
                "B"));
    assertTrue(message.contains("sandbox"), message);
  }

  @Test
  void identityPromptAndABodyTogetherAreAConflict() {
    String message =
        problemOf(
            "both",
            agent(
                "description: d\nprovider: {name: mock, model: m}\nidentity: {prompt: hi}",
                "Body"));
    assertTrue(message.contains("identity.prompt conflicts"), message);
  }

  @Test
  void anExtraValidatorCanRejectTheFinishedProfile() {
    AgentLoader strict =
        AgentFixtures.loader(
            temp.resolve("profiles"),
            profile -> {
              if (!profile.schedules().isEmpty()) {
                throw new AgentDefinitionException(
                    "schedules: cron is not a valid six-field cron expression");
              }
            });
    Path dir = AgentFixtures.write(root, "daily-reconcile", DAILY);
    String message =
        assertThrows(AgentDefinitionException.class, () -> strict.load(dir)).getMessage();
    assertTrue(message.contains("agents/daily-reconcile/AGENT.md"), message);
    assertTrue(message.contains("six-field cron"), message);
  }

  @Test
  void aByteOrderMarkAndWindowsLineEndingsAreAccepted() throws IOException {
    Path dir = root.resolve("crlf");
    Files.createDirectories(dir);
    String text =
        "﻿"
            + agent("description: d\nprovider: {name: mock, model: m}", "Body line")
                .replace("\n", "\r\n");
    Files.write(dir.resolve("AGENT.md"), text.getBytes(StandardCharsets.UTF_8));

    AgentDefinition definition = loader.load(dir);

    assertEquals("Body line", definition.instructions());
  }

  @Test
  void bytesThatAreNotUtf8AreRefused() throws IOException {
    Path dir = root.resolve("latin");
    Files.createDirectories(dir);
    Files.write(
        dir.resolve("AGENT.md"), new byte[] {'-', '-', '-', '\n', (byte) 0xE9, (byte) 0xFF});
    String message =
        assertThrows(AgentDefinitionException.class, () -> loader.load(dir)).getMessage();
    assertTrue(message.contains("not valid UTF-8"), message);
  }

  @Test
  void aHugeFileIsRefusedBeforeItIsParsed() {
    String message =
        problemOf(
            "huge",
            agent(
                "description: d\nprovider: {name: mock, model: m}", "x".repeat(AgentMd.MAX_CHARS)));
    assertTrue(message.contains("larger than"), message);
  }

  @Test
  void anAgentMdThatIsALinkIsRefused() throws IOException {
    Path dir = root.resolve("linked");
    Files.createDirectories(dir);
    Path real = AgentFixtures.write(root, "real", DAILY).resolve("AGENT.md");
    boolean linked = true;
    try {
      Files.createSymbolicLink(dir.resolve("AGENT.md"), real);
    } catch (UnsupportedOperationException | IOException | SecurityException cannotLink) {
      linked = false; // Windows accounts without the symlink privilege cannot create one
    }
    assumeTrue(linked, "this account cannot create symbolic links");
    String message =
        assertThrows(AgentDefinitionException.class, () -> loader.load(dir)).getMessage();
    assertTrue(message.contains("not a link"), message);
  }
}
