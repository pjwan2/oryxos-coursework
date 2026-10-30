package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.DemoSupport.GithubApi;
import io.oryxos.DemoSupport.Receiver;
import io.oryxos.LocalTestServers.FakeModelServer;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Demo 3, tier A: the real {@code examples/agents/github-daily} directory. The trusted Python
 * script really runs through the real {@code shell_exec} tool and its sandbox; only GitHub (a local
 * fixture), the model (scripted) and the chat group (a local receiver) are replaced.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GitHubDailyAgentTest {
  private static final String TASK = "github-daily.morning";
  private static final String AGENT = "github-daily";
  private static final String PYTHON = GitHubTrendingScriptTest.findPython();
  private static final E2eWorkspace WS = new E2eWorkspace("oryxos-c5-github");
  private static final GithubApi GITHUB = new GithubApi();
  private static final Receiver GROUP = new Receiver();
  private static final FakeModelServer MODEL = new FakeModelServer(DemoSupport::brain, 0);
  private static final String ORIGINAL_ARGS = "\"--limit\",\"5\"]";

  static String installed(String md) {
    String script =
        DemoSupport.slash(WS.agents.resolve(AGENT).resolve("scripts/github_trending.py"));
    String edited = DemoSupport.common(md, GROUP.url(), "0 30 9 1 1 *");
    String command = PYTHON == null ? "python" : PYTHON;
    edited =
        DemoSupport.replaceOnce(
            edited, "\\.oryxos/agents/github-daily/scripts/github_trending\\.py", regex(script));
    edited =
        DemoSupport.replaceOnce(
            edited, ".oryxos/agents/github-daily/scripts/github_trending.py", script);
    edited = DemoSupport.replaceOnce(edited, "- name: python", "- name: " + command);
    edited =
        DemoSupport.replaceOnce(
            edited, "\"command\":\"python\"", "\"command\":\"" + command + "\"");
    edited =
        DemoSupport.replaceOnce(
            edited,
            "'https://api\\.github\\.com'",
            "'http://127\\.0\\.0\\.1:[0-9]{2,5}'\n          - '--today'\n          - '2026-03-05'");
    edited = DemoSupport.replaceOnce(edited, "max_args: 5", "max_args: 7");
    return DemoSupport.replaceOnce(
        edited,
        ORIGINAL_ARGS,
        "\"--limit\",\"5\",\"--api-base\",\""
            + GITHUB.baseUrl()
            + "\",\"--today\",\"2026-03-05\"]");
  }

  private static String regex(String literal) {
    return literal.replace(".", "\\.");
  }

  static {
    DemoSupport.install(WS.agents, AGENT, GitHubDailyAgentTest::installed);
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry registry) {
    WS.register(
        registry,
        MODEL.baseUrl(),
        Map.of(
            "oryxos.sandbox.commands",
            PYTHON == null ? "python" : PYTHON,
            "oryxos.agents-watch",
            "false"));
  }

  @AfterAll
  static void stop() {
    MODEL.close();
    GITHUB.close();
    GROUP.close();
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    assumeTrue(PYTHON != null, "no Python interpreter on PATH");
    GITHUB.reset();
    GROUP.reset();
    MODEL.reset();
  }

  private void tell(String message) throws Exception {
    var response =
        mvc.perform(
                post("/api/v1/agents/" + AGENT + "/invoke")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"userId\":\"reader\",\"message\":" + DemoSupport.json(message) + "}"))
            .andReturn()
            .getResponse();
    assertEquals(200, response.getStatus(), response.getContentAsString());
  }

  private void putAgentMd(String agentMd) throws Exception {
    var response =
        mvc.perform(
                put("/api/v1/agents/" + AGENT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"agentMd\":" + DemoSupport.json(agentMd) + "}"))
            .andReturn()
            .getResponse();
    assertEquals(200, response.getStatus(), response.getContentAsString());
  }

  private String currentAgentMd() throws Exception {
    return Files.readString(WS.agents.resolve(AGENT).resolve("AGENT.md"));
  }

  @Test
  @Order(1)
  void theExampleLoadsWithItsScriptListedByNameOnly() throws Exception {
    JsonNode view =
        LocalTestServers.JSON
            .readTree(
                mvc.perform(get("/api/v1/agents/" + AGENT))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("data");
    assertEquals(
        List.of("shell_exec", "notify", "save_memory", "recall_memory").stream().sorted().toList(),
        LocalTestServers.JSON.convertValue(view.get("tools"), List.class).stream()
            .sorted()
            .toList());
    assertEquals(
        List.of("scripts/github_trending.py"),
        LocalTestServers.JSON.convertValue(view.get("resources"), List.class));
  }

  @Test
  @Order(2)
  void theScriptRunsThroughTheShellToolAndItsJsonFeedsTheThreePartReport() throws Exception {
    JsonNode run = DemoSupport.runNow(mvc, TASK);

    assertEquals(3, MODEL.requestCount(), "run the script, notify, final answer");
    assertEquals(
        List.of("shell_exec:ok:null", "notify:ok:null"),
        DemoSupport.toolRows(jdbc, run.get("executionId").asText()));
    assertFalse(
        GITHUB.queries().isEmpty(), "the Python script itself called the (fixture) GitHub API");
    assertTrue(GITHUB.queries().getFirst().contains("created:>=2026-03-04"));
    String afterScript = MODEL.requests().get(1).get("messages").toString();
    assertTrue(
        afterScript.contains("acme/fresh-db") && afterScript.contains("lab/ai-agents"),
        "the script's JSON output is in the next model request");
    String report = GROUP.posts().getFirst().text();
    assertEquals("GitHub 日报", GROUP.posts().getFirst().title());
    assertTrue(
        report.contains("今日热门 Top 5")
            && report.contains("本月热门 Top 5")
            && report.contains("AI 项目重点"),
        report);
    assertTrue(report.contains("acme/fresh-db ★910") && report.contains("acme/big-month ★4800"));
    assertTrue(report.contains("不是官方 Trending"), "the report says what the ranking is");
    assertEquals(
        "COMPLETED",
        DemoSupport.outcome(mvc, TASK, run.get("id").asLong()).get("outcome").asText());
  }

  @Test
  @Order(3)
  void theScriptSourceIsNeverPutIntoAnyPrompt() throws Exception {
    DemoSupport.runNow(mvc, TASK);

    for (JsonNode request : MODEL.requests()) {
      String everything = request.toString();
      assertFalse(everything.contains("def build("), "no script source in the prompt");
      assertFalse(everything.contains("MAX_RESPONSE_BYTES"));
    }
    String prompt = LocalTestServers.systemContent(MODEL.requests().getFirst());
    assertTrue(prompt.contains("scripts/github_trending.py"), "only its name is listed");
    assertEquals(
        List.of("notify", "recall_memory", "save_memory", "shell_exec"),
        DemoSupport.offeredTools(MODEL.requests().getFirst()));
  }

  @Test
  @Order(4)
  void aPreferenceInTheAgentsOwnMemoryChangesTheAiHighlights() throws Exception {
    DemoSupport.runNow(mvc, TASK);
    String before = aiSection(GROUP.posts().getFirst().text());
    GROUP.reset();

    tell("记住：优先关注 RAG 相关项目");
    assertTrue(Files.readString(WS.agents.resolve(AGENT).resolve("MEMORY.md")).contains("RAG"));
    MODEL.reset();
    DemoSupport.runNow(mvc, TASK);
    String after = aiSection(GROUP.posts().getFirst().text());

    assertTrue(before.indexOf("lab/ai-agents") < before.indexOf("lab/rag-engine"));
    assertTrue(
        after.indexOf("lab/rag-engine") < after.indexOf("lab/ai-agents"),
        "the preference moved RAG first: " + after);
    assertTrue(LocalTestServers.systemContent(MODEL.requests().getFirst()).contains("RAG"));
  }

  private static String aiSection(String report) {
    return report.substring(report.indexOf("AI 项目重点"));
  }

  @Test
  @Order(5)
  void anyOtherProgramOrArgumentIsRefusedByTheSandboxAndNoProcessStarts() throws Exception {
    String original = currentAgentMd();
    try {
      putAgentMd(
          DemoSupport.replaceOnce(
              original,
              "{\"command\":\"" + PYTHON + "\",\"args\":[",
              "{\"command\":\"" + PYTHON + "\",\"args\":[\"-c\",\"print(1)\","));
      JsonNode wrongArgs = DemoSupport.runNow(mvc, TASK);
      assertEquals(
          List.of("shell_exec:fail:SandboxDenied", "notify:ok:null"),
          DemoSupport.toolRows(jdbc, wrongArgs.get("executionId").asText()));
      assertTrue(GITHUB.queries().isEmpty(), "the script did not run");
      assertEquals(
          "COMPLETED_WITH_TOOL_ISSUES",
          DemoSupport.outcome(mvc, TASK, wrongArgs.get("id").asLong()).get("outcome").asText());

      GROUP.reset();
      putAgentMd(
          DemoSupport.replaceOnce(
              original, "\"command\":\"" + PYTHON + "\"", "\"command\":\"powershell\""));
      JsonNode wrongProgram = DemoSupport.runNow(mvc, TASK);
      assertEquals(
          "shell_exec:fail:SandboxDenied",
          DemoSupport.toolRows(jdbc, wrongProgram.get("executionId").asText()).getFirst());
      assertTrue(
          GROUP.posts().getFirst().text().startsWith("GitHub 日报失败"),
          GROUP.posts().getFirst().text());
    } finally {
      putAgentMd(original);
    }
  }

  @Test
  @Order(6)
  void aRateLimitedScriptIsReportedAsAFailureWithItsMessage() throws Exception {
    GITHUB.mode("ratelimit");

    JsonNode run = DemoSupport.runNow(mvc, TASK);

    assertEquals(
        List.of("shell_exec:fail:ToolReturnedFailure", "notify:ok:null"),
        DemoSupport.toolRows(jdbc, run.get("executionId").asText()));
    String report = GROUP.posts().getFirst().text();
    assertTrue(report.startsWith("GitHub 日报失败") && report.contains("rate limit"), report);
    assertFalse(report.contains("★"), "no repository is invented");
    assertEquals(
        "COMPLETED_WITH_TOOL_ISSUES",
        DemoSupport.outcome(mvc, TASK, run.get("id").asLong()).get("outcome").asText());
  }

  @Test
  @Order(7)
  void anEmptyGithubAnswerIsReportedAsNoData() throws Exception {
    GITHUB.mode("empty");

    DemoSupport.runNow(mvc, TASK);

    assertEquals("今天没有查到数据", GROUP.posts().getFirst().text());
  }

  @Test
  @Order(8)
  void instructionsInsideARepositoryDescriptionStayDataAndNoSecretIsPrinted() throws Exception {
    GITHUB.mode("dirty");

    DemoSupport.runNow(mvc, TASK);

    for (JsonNode request : MODEL.requests()) {
      assertFalse(LocalTestServers.systemContent(request).contains("IGNORE ALL PREVIOUS"));
    }
    assertTrue(
        MODEL.requests().get(1).get("messages").toString().contains("first line second line"),
        "cleaned text reaches the model only as tool data");
    assertFalse(
        Pattern.compile("[\\x00-\\x08\\x0b-\\x1f]")
            .matcher(GROUP.posts().getFirst().text())
            .find());
  }
}
