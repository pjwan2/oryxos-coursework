package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.cli.CliRuntime;
import io.oryxos.core.profile.AgentConflictException;
import io.oryxos.core.profile.AgentDefinitionException;
import io.oryxos.provider.ProfileRegistry;
import io.oryxos.provider.agent.AgentLifecycleService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Lesson 29 on the real application: an {@code AGENT.md} directory becomes a running agent with no
 * Java change, no new bean and no repackaging, on the same Profile, registry, AgentService,
 * scheduler and sandbox as every other agent. The model is the offline Mock provider.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentPluginIntegrationTest {
  private static final E2eWorkspace WS = new E2eWorkspace("oryxos-agent-plugin");

  private static String agentMd(String name, String sandboxRoots, String body, String extra) {
    return "---\nname: "
        + name
        + "\ndescription: plugin agent "
        + name
        + "\nprovider: {name: mock, model: m, temperature: 0.0}\ntools: [file_read]\n"
        + "settings:\n  sandbox:\n    read_roots: ["
        + sandboxRoots
        + "]\n"
        + extra
        + "---\n"
        + body
        + "\n";
  }

  private static String quoted(Path dir) {
    return "\"" + E2eWorkspace.slash(dir) + "\"";
  }

  private static void write(String name, String agentMd) {
    try {
      Path dir = Files.createDirectories(WS.agents.resolve(name));
      Files.writeString(dir.resolve("AGENT.md"), agentMd);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static {
    try {
      Files.writeString(WS.allowed.resolve("note.txt"), "NOTE-FROM-ALLOWED-DIRECTORY");
      Files.writeString(
          Files.createDirectories(WS.allowed.resolve("open")).resolve("note.txt"), "OPEN-NOTE");
      Files.writeString(
          Files.createDirectories(WS.allowed.resolve("private")).resolve("secret.txt"),
          "SECRET-FROM-PRIVATE-DIRECTORY");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    write(
        "daily-reconcile",
        agentMd(
            "daily-reconcile",
            quoted(WS.allowed),
            "Reconcile the ledger. INSTRUCTIONS-V1",
            "schedules:\n  - {id: nightly, cron: \"0 0 2 1 1 *\", zone: UTC, message: Reconcile}\n"));
    write("broken", "this file has no frontmatter");
    write("twin", agentMd("twin", quoted(WS.allowed), "from AGENT.md", ""));
    WS.profile("twin", "mock", List.of(), null, null);
    WS.profile("legacy-one", "mock", List.of(), null, null);
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry registry) {
    WS.register(registry, "http://127.0.0.1:9");
  }

  @Autowired MockMvc mvc;
  @Autowired ProfileRegistry registry;
  @Autowired AgentLifecycleService lifecycle;
  @Autowired JdbcTemplate jdbc;
  @Autowired io.oryxos.scheduler.AgentScheduler scheduler;

  private String invoke(String agent, String user, String message) throws Exception {
    String body =
        mvc.perform(
                post("/api/v1/agents/" + agent + "/invoke")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"userId\":\"" + user + "\",\"message\":" + json(message) + "}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return body;
  }

  private static String json(String text) {
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private String readNote(String agent, String user, Path file) throws Exception {
    return invoke(agent, user, "tool:file_read {\"path\":" + json(E2eWorkspace.slash(file)) + "}");
  }

  @Test
  void anAgentDirectoryIsLoadedAtStartupAndListedByRestAndCli() throws Exception {
    assertTrue(registry.exists("daily-reconcile"));
    mvc.perform(get("/api/v1/profiles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[?(@.name=='daily-reconcile')].tools[0]").value("file_read"))
        .andExpect(jsonPath("$.data[?(@.name=='daily-reconcile')].scheduleCount").value(1))
        .andExpect(jsonPath("$.data[?(@.name=='legacy-one')]").exists())
        .andExpect(jsonPath("$.data[?(@.name=='broken')]").doesNotExist());

    BootCliRuntime cli = new BootCliRuntime(WS.properties("http://127.0.0.1:9"));
    List<String> names = cli.listProfiles().stream().map(CliRuntime.ProfileSummary::name).toList();
    assertTrue(names.contains("daily-reconcile"), names.toString());
    assertTrue(names.contains("legacy-one"), names.toString());
    assertFalse(names.contains("broken"));
  }

  @Test
  void theAgentRunsOnTheNormalPathAndItsInstructionsReachTheModelPrompt() throws Exception {
    String reply = invoke("daily-reconcile", "u1", "hello plugin");
    assertTrue(reply.contains("Mock response: hello plugin"), reply);

    String system = registry.get("daily-reconcile").identity().get("prompt").toString();
    assertTrue(system.contains("INSTRUCTIONS-V1"));
    assertEquals(
        1,
        E2eWorkspace.count(jdbc, "llm_calls", "api:u1:daily-reconcile"),
        "one audited model call through the normal provider path");
  }

  @Test
  void aDeclaredScheduleIsRegisteredWithTheExistingScheduler() throws Exception {
    mvc.perform(get("/api/v1/schedules"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data[?(@.taskId=='daily-reconcile.nightly')].cron").value("0 0 2 1 1 *"))
        .andExpect(jsonPath("$.data[?(@.taskId=='daily-reconcile.nightly')].zone").value("UTC"));
    assertTrue(
        scheduler.list().stream().anyMatch(v -> v.taskId().equals("daily-reconcile.nightly")));
  }

  @Test
  void anInvalidDirectoryIsReportedAndDoesNotStopTheOthers() {
    assertFalse(registry.exists("broken"));
    assertTrue(lifecycle.problems().get("broken").contains("must start with"));
    assertTrue(registry.exists("daily-reconcile"));
  }

  @Test
  void aNameDefinedByYamlAndByAgentMdKeepsTheLegacyProfileAndSaysSo() {
    assertEquals("end-to-end test agent", registry.get("twin").description());
    assertTrue(lifecycle.problems().get("twin").contains("defined twice"));
    assertThrows(AgentConflictException.class, () -> lifecycle.register(WS.agents.resolve("twin")));
  }

  @Test
  void aDirectoryCopiedInLaterBecomesAnAgentWithoutRestartAndItsScheduleIsRegistered()
      throws Exception {
    write(
        "late-agent",
        agentMd(
            "late-agent",
            quoted(WS.allowed),
            "Late. LATE-V1",
            "schedules:\n  - {id: weekly, cron: \"0 0 3 1 1 *\", zone: Asia/Shanghai, message: Go}\n"));

    lifecycle.register(WS.agents.resolve("late-agent"));

    assertTrue(registry.exists("late-agent"));
    assertTrue(invoke("late-agent", "u2", "hi").contains("Mock response: hi"));
    assertTrue(scheduler.list().stream().anyMatch(v -> v.taskId().equals("late-agent.weekly")));
    assertEquals(
        "Asia/Shanghai",
        scheduler.list().stream()
            .filter(v -> v.taskId().equals("late-agent.weekly"))
            .findFirst()
            .orElseThrow()
            .zone());
  }

  @Test
  void aBadScheduleOrToolIsRefusedWithAReadableReasonAndNothingIsRegistered() {
    write(
        "bad-cron",
        agentMd(
            "bad-cron",
            quoted(WS.allowed),
            "x",
            "schedules:\n  - {id: s, cron: \"every day\", zone: UTC, message: m}\n"));
    AgentDefinitionException cron =
        assertThrows(
            AgentDefinitionException.class,
            () -> lifecycle.register(WS.agents.resolve("bad-cron")));
    assertTrue(
        cron.getMessage().contains("cron is not a valid six-field cron expression"),
        cron.getMessage());
    assertFalse(registry.exists("bad-cron"));

    write(
        "bad-tool",
        agentMd("bad-tool", quoted(WS.allowed), "x", "").replace("[file_read]", "[teleport]"));
    AgentDefinitionException tool =
        assertThrows(
            AgentDefinitionException.class,
            () -> lifecycle.register(WS.agents.resolve("bad-tool")));
    assertTrue(tool.getMessage().contains("tool 'teleport' is not registered"), tool.getMessage());
    assertTrue(
        tool.getMessage().contains("file_read"), "lists what is installed: " + tool.getMessage());

    write(
        "bad-provider",
        agentMd("bad-provider", quoted(WS.allowed), "x", "")
            .replace("name: mock", "name: gpt-nowhere"));
    AgentDefinitionException provider =
        assertThrows(
            AgentDefinitionException.class,
            () -> lifecycle.register(WS.agents.resolve("bad-provider")));
    assertTrue(
        provider.getMessage().contains("unknown provider: gpt-nowhere"), provider.getMessage());
  }

  @Test
  void tighteningAnAgentsDirectoryPermissionDeniesTheNextCallImmediately() throws Exception {
    Path secret = WS.allowed.resolve("private/secret.txt");
    write("tightener", agentMd("tightener", quoted(WS.allowed), "Reads files. T-V1", ""));
    lifecycle.register(WS.agents.resolve("tightener"));

    String allowedBefore = readNote("tightener", "u3", secret);
    assertTrue(allowedBefore.contains("SECRET-FROM-PRIVATE-DIRECTORY"), allowedBefore);

    write(
        "tightener",
        agentMd("tightener", quoted(WS.allowed.resolve("open")), "Reads files. T-V2", ""));
    lifecycle.reload("tightener");

    String denied = readNote("tightener", "u3", secret);
    assertFalse(
        denied.contains("SECRET-FROM-PRIVATE-DIRECTORY"), "withdrawn permission: " + denied);
    assertTrue(denied.contains("SANDBOX_DENIED"), denied);
    assertTrue(
        readNote("tightener", "u3", WS.allowed.resolve("open/note.txt")).contains("OPEN-NOTE"));
    Integer deniedRows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tool_invocations WHERE session_id = ? AND success = 0"
                + " AND error_category = 'SandboxDenied'",
            Integer.class,
            "api:u3:tightener");
    assertEquals(1, deniedRows, "the refusal is audited as a sandbox denial");
  }

  @Test
  void aChangedBodyIsUsedByTheNextRunWithNoStaleInstructions() throws Exception {
    write("editor", agentMd("editor", quoted(WS.allowed), "First wording. WORDING-ONE", ""));
    lifecycle.register(WS.agents.resolve("editor"));
    assertTrue(registry.get("editor").identity().get("prompt").toString().contains("WORDING-ONE"));

    write("editor", agentMd("editor", quoted(WS.allowed), "Second wording. WORDING-TWO", ""));
    lifecycle.reload("editor");

    String prompt = registry.get("editor").identity().get("prompt").toString();
    assertTrue(prompt.contains("WORDING-TWO"));
    assertFalse(prompt.contains("WORDING-ONE"));
    assertTrue(invoke("editor", "u4", "after edit").contains("Mock response: after edit"));
  }
}
