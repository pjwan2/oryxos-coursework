package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.DemoSupport.Receiver;
import io.oryxos.DemoSupport.WeatherApi;
import io.oryxos.LocalTestServers.FakeModelServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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
 * Demo 1, tier A: the real {@code examples/agents/weather-daily/AGENT.md}, created through the REST
 * API, run on the real stack. Only the outside world is replaced: the model is scripted, the
 * weather API and the chat group are local servers. Open-Meteo itself is tier B (see the runbook).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WeatherDailyAgentTest {
  private static final String TASK = "weather-daily.morning";
  private static final E2eWorkspace WS = new E2eWorkspace("oryxos-c5-weather");
  private static final WeatherApi WEATHER = new WeatherApi();
  private static final Receiver GROUP = new Receiver();
  private static final FakeModelServer MODEL = new FakeModelServer(DemoSupport::brain, 0);

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry registry) {
    WS.register(
        registry,
        MODEL.baseUrl(),
        Map.of(
            "oryxos.sandbox.hosts",
            "127.0.0.1,api.open-meteo.com",
            "oryxos.agents-watch",
            "false"));
  }

  @AfterAll
  static void stop() {
    MODEL.close();
    WEATHER.close();
    GROUP.close();
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  /** The example, with the fake model, the local receiver, the local weather API, a far cron. */
  private static String agentMd(String forecastBase) {
    String md = DemoSupport.common(read(), GROUP.url(), "0 0 8 1 1 *");
    return DemoSupport.replaceOnce(md, "https://api.open-meteo.com/v1/forecast", forecastBase);
  }

  private static String read() {
    try {
      return Files.readString(DemoSupport.EXAMPLES.resolve("weather-daily/AGENT.md"));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String q(String text) {
    return DemoSupport.json(text);
  }

  @BeforeEach
  void reset() {
    WEATHER.reset();
    GROUP.reset();
    MODEL.reset();
  }

  private void update(String agentMd) throws Exception {
    var response =
        mvc.perform(
                put("/api/v1/agents/weather-daily")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"agentMd\":" + q(agentMd) + "}"))
            .andReturn()
            .getResponse();
    assertEquals(200, response.getStatus(), response.getContentAsString());
  }

  @Test
  @Order(1)
  void theAgentIsCreatedThroughTheApiAndNeedsNothingButItsAgentMd() throws Exception {
    var created =
        mvc.perform(
                post("/api/v1/agents")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"weather-daily\",\"description\":\"daily weather\"}"))
            .andReturn()
            .getResponse();
    assertEquals(201, created.getStatus(), created.getContentAsString());
    update(agentMd(WEATHER.forecastUrl()));

    try (Stream<Path> files = Files.list(WS.agents.resolve("weather-daily"))) {
      assertEquals(List.of("AGENT.md"), files.map(p -> p.getFileName().toString()).toList());
    }
    JsonNode view =
        LocalTestServers.JSON
            .readTree(
                mvc.perform(get("/api/v1/agents/weather-daily"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("data");
    assertEquals(
        List.of("http_get", "notify"),
        LocalTestServers.JSON.convertValue(view.get("tools"), List.class));
    assertEquals(1, view.get("schedules").size());
    assertEquals("morning", view.at("/schedules/0/id").asText());
    assertEquals("Asia/Shanghai", view.at("/schedules/0/zone").asText());
    assertFalse(
        view.toString().contains(GROUP.url()), "the webhook address is not shown by the API");
  }

  @Test
  @Order(2)
  void aManualRunFetchesTheWeatherAndSendsTheAdviceAndEverythingIsAudited() throws Exception {
    int llmBefore = count("llm_calls WHERE session_id = 'scheduler:scheduler:weather-daily'");

    JsonNode run = DemoSupport.runNow(mvc, TASK);

    assertTrue(run.get("success").asBoolean());
    String executionId = run.get("executionId").asText();
    assertEquals(3, MODEL.requestCount(), "tool request, notify request, final answer");
    assertEquals(
        llmBefore + 3, count("llm_calls WHERE session_id = 'scheduler:scheduler:weather-daily'"));
    assertEquals(1, WEATHER.hits());
    assertTrue(WEATHER.queries().getFirst().contains("latitude=39.9042"));
    assertEquals(
        List.of("http_get:ok:null", "notify:ok:null"), DemoSupport.toolRows(jdbc, executionId));
    assertEquals(1, GROUP.posts().size());
    var post = GROUP.posts().getFirst();
    assertEquals("今日天气与穿搭", post.title());
    assertTrue(
        post.text().contains("6.5℃") && post.text().contains("带伞") && post.text().contains("防风"),
        post.text());

    JsonNode outcome = DemoSupport.outcome(mvc, TASK, run.get("id").asLong());
    assertEquals("COMPLETED", outcome.get("outcome").asText());
    assertEquals(2, outcome.get("toolCalls").asInt());
    assertEquals("execution_id", outcome.get("matchedBy").asText());
  }

  @Test
  @Order(3)
  void theSessionKeepsTheWholeConversationOfTheRun() throws Exception {
    DemoSupport.runNow(mvc, TASK);

    JsonNode session =
        LocalTestServers.JSON
            .readTree(
                mvc.perform(get("/api/v1/sessions/scheduler:scheduler:weather-daily"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("data");
    JsonNode messages = session.get("messages");
    int n = messages.size();
    assertTrue(n >= 6);
    List<String> roles = new java.util.ArrayList<>();
    for (int i = n - 6; i < n; i++) {
      roles.add(messages.get(i).get("role").asText());
    }
    assertEquals(List.of("USER", "ASSISTANT", "TOOL", "ASSISTANT", "TOOL", "ASSISTANT"), roles);
    assertEquals("http_get", messages.get(n - 5).at("/toolCalls/0/name").asText());
    assertEquals("notify", messages.get(n - 3).at("/toolCalls/0/name").asText());
    assertEquals("已发送", messages.get(n - 1).get("content").asText());
  }

  @Test
  @Order(4)
  void theModelIsOfferedExactlyTheTwoDeclaredToolsAndTheBodyIsThePrompt() throws Exception {
    DemoSupport.runNow(mvc, TASK);

    JsonNode first = MODEL.requests().getFirst();
    assertEquals(List.of("http_get", "notify"), DemoSupport.offeredTools(first));
    String system = LocalTestServers.systemContent(first);
    assertTrue(system.contains("每日天气与穿搭助手") && system.contains("Forecast URL: "));
    assertFalse(
        system.contains("notify_channels")
            || system.contains("provider:")
            || system.contains("allow_private_networks"),
        "the frontmatter is configuration, not prompt text");
    assertFalse(system.contains(GROUP.url()), "the webhook address never reaches the model");
  }

  @Test
  @Order(5)
  void aHostOutsideThePermissionIsDeniedAndTheMessageSaysTheWeatherFailed() throws Exception {
    update(agentMd("http://evil.example/v1/forecast"));

    JsonNode run = DemoSupport.runNow(mvc, TASK);

    assertTrue(run.get("success").asBoolean(), "technically the run finished");
    assertEquals(0, WEATHER.hits());
    assertEquals(
        List.of("http_get:fail:SandboxDenied", "notify:ok:null"),
        DemoSupport.toolRows(jdbc, run.get("executionId").asText()));
    assertEquals(1, GROUP.posts().size());
    assertTrue(
        GROUP.posts().getFirst().text().startsWith("天气获取失败"), GROUP.posts().getFirst().text());
    assertFalse(GROUP.posts().getFirst().text().contains("穿"), "no advice is invented");
    JsonNode outcome = DemoSupport.outcome(mvc, TASK, run.get("id").asLong());
    assertEquals("COMPLETED_WITH_TOOL_ISSUES", outcome.get("outcome").asText());
    assertEquals(1, outcome.get("toolDenied").asInt());
    assertEquals(1, outcome.get("toolSucceeded").asInt());
    update(agentMd(WEATHER.forecastUrl()));
  }

  @Test
  @Order(6)
  void aWeatherApiOutageIsReportedAsAFailureNotAsAdvice() throws Exception {
    WEATHER.answerWith(500, "{}");

    JsonNode run = DemoSupport.runNow(mvc, TASK);

    assertEquals(
        List.of("http_get:fail:ToolReturnedFailure", "notify:ok:null"),
        DemoSupport.toolRows(jdbc, run.get("executionId").asText()));
    assertTrue(
        GROUP.posts().getFirst().text().startsWith("天气获取失败：HTTP status 500"),
        GROUP.posts().getFirst().text());
    assertEquals(
        "COMPLETED_WITH_TOOL_ISSUES",
        DemoSupport.outcome(mvc, TASK, run.get("id").asLong()).get("outcome").asText());
  }

  @Test
  @Order(7)
  void aDeadChatGroupMeansTheNotificationFailedEvenThoughTheRunFinished() throws Exception {
    GROUP.failWith(500);

    JsonNode run = DemoSupport.runNow(mvc, TASK);

    assertTrue(run.get("success").asBoolean());
    assertEquals(0, GROUP.posts().size());
    assertEquals(
        List.of("http_get:ok:null", "notify:fail:ToolReturnedFailure"),
        DemoSupport.toolRows(jdbc, run.get("executionId").asText()));
    JsonNode outcome = DemoSupport.outcome(mvc, TASK, run.get("id").asLong());
    assertEquals("COMPLETED_WITH_TOOL_ISSUES", outcome.get("outcome").asText());
    assertEquals(1, outcome.get("toolFailed").asInt());
    assertNotNull(outcome.get("note"));
    assertTrue(
        outcome.get("note").asText().contains("not a confirmed business success"),
        "the report says it is NOT a business success");
  }

  @Test
  @Order(8)
  void aWeatherAnswerWithoutTheExpectedFieldsIsNotTurnedIntoAdvice() throws Exception {
    WEATHER.answerWith(200, "{\"unexpected\":true}");

    DemoSupport.runNow(mvc, TASK);

    assertTrue(
        GROUP.posts().getFirst().text().startsWith("天气获取失败"), GROUP.posts().getFirst().text());
  }

  private int count(String fromWhere) {
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + fromWhere, Integer.class);
    return n == null ? 0 : n;
  }
}
