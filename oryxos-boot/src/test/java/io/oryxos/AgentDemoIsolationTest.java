package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.agent.AgentToolExecutor;
import io.oryxos.core.agent.ToolAction;
import io.oryxos.provider.ProfileRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;

/** The three demo agents share one application but not memory, tools, permissions or sessions. */
class AgentDemoIsolationTest extends AbstractAgentDemoIT {
  private void tell(String agent, String message) throws Exception {
    var response =
        mvc.perform(
                post("/api/v1/agents/" + agent + "/invoke")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"userId\":\"owner\",\"message\":" + DemoSupport.json(message) + "}"))
            .andReturn()
            .getResponse();
    assertEquals(200, response.getStatus(), response.getContentAsString());
  }

  private JsonNode memoryOf(String agent) throws Exception {
    return LocalTestServers.JSON
        .readTree(
            mvc.perform(get("/api/v1/agents/" + agent + "/memory"))
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("data");
  }

  @Test
  void eachAgentHasItsOwnMemoryFileAndTheOthersNeverSeeIt() throws Exception {
    tell("daily-tech-digest", "记住：优先关注TECH-ONLY-PREFERENCE");
    tell("github-daily", "记住：优先关注GITHUB-ONLY-PREFERENCE");

    Path tech = WS.agents.resolve("daily-tech-digest/MEMORY.md");
    Path github = WS.agents.resolve("github-daily/MEMORY.md");
    assertTrue(Files.readString(tech).contains("TECH-ONLY-PREFERENCE"));
    assertFalse(Files.readString(tech).contains("GITHUB-ONLY-PREFERENCE"));
    assertTrue(Files.readString(github).contains("GITHUB-ONLY-PREFERENCE"));
    assertFalse(Files.readString(github).contains("TECH-ONLY-PREFERENCE"));
    assertFalse(
        Files.exists(WS.agents.resolve("weather-daily/MEMORY.md")),
        "the weather agent has no memory at all");
    assertFalse(
        Files.exists(WS.memoryFile) && Files.readString(WS.memoryFile).contains("ONLY-PREFERENCE"));

    assertTrue(
        memoryOf("daily-tech-digest").get("core").toString().contains("TECH-ONLY-PREFERENCE"));
    assertFalse(
        memoryOf("daily-tech-digest").get("core").toString().contains("GITHUB-ONLY-PREFERENCE"));
    assertTrue(memoryOf("github-daily").get("core").toString().contains("GITHUB-ONLY-PREFERENCE"));
    assertFalse(memoryOf("github-daily").get("core").toString().contains("TECH-ONLY-PREFERENCE"));
  }

  @Test
  void aPreferenceOfOneAgentNeverReachesThePromptOfAnother() throws Exception {
    tell("daily-tech-digest", "记住：优先关注PROMPT-LEAK-CHECK");
    MODEL.reset();

    DemoSupport.runNow(mvc, TECH_TASK);
    DemoSupport.runNow(mvc, WEATHER_TASK);
    requirePython();
    DemoSupport.runNow(mvc, GITHUB_TASK);

    boolean sawItInTech = false;
    for (JsonNode request : MODEL.requests()) {
      String system = LocalTestServers.systemContent(request);
      if (system.contains("每日科技日报编辑")) {
        sawItInTech |= system.contains("PROMPT-LEAK-CHECK");
      } else {
        assertFalse(
            system.contains("PROMPT-LEAK-CHECK"),
            "another agent's prompt carries the tech preference");
      }
    }
    assertTrue(sawItInTech, "the tech agent's own prompt does carry it");
  }

  @Test
  void eachAgentIsOfferedOnlyItsOwnToolsAndNeverAFileWriteOrAGeneralShell() throws Exception {
    requirePython();
    DemoSupport.runNow(mvc, WEATHER_TASK);
    DemoSupport.runNow(mvc, TECH_TASK);
    DemoSupport.runNow(mvc, GITHUB_TASK);

    List<String> weather = null;
    List<String> tech = null;
    List<String> github = null;
    for (JsonNode request : MODEL.requests()) {
      String system = LocalTestServers.systemContent(request);
      if (system.contains(DemoSupport.WEATHER_MARK)) {
        weather = DemoSupport.offeredTools(request);
      } else if (system.contains(DemoSupport.TECH_MARK)) {
        tech = DemoSupport.offeredTools(request);
      } else if (system.contains(DemoSupport.GITHUB_MARK)) {
        github = DemoSupport.offeredTools(request);
      }
    }
    assertEquals(List.of("http_get", "notify"), weather);
    assertEquals(List.of("file_read", "http_get", "notify", "recall_memory", "save_memory"), tech);
    assertEquals(List.of("notify", "recall_memory", "save_memory", "shell_exec"), github);
    for (List<String> tools : List.of(weather, tech, github)) {
      assertFalse(tools.contains("file_write"));
    }
    assertFalse(
        weather.contains("shell_exec") || tech.contains("shell_exec"),
        "only github-daily has a shell tool");
  }

  @Autowired
  @Qualifier("lesson17Tools")
  AgentToolExecutor tools;

  @Autowired ProfileRegistry registry;

  private io.oryxos.core.agent.ToolOutput as(String agent, String tool, String args) {
    return tools.execute(
        "iso:probe:" + agent, registry.get(agent), new ToolAction("c", tool, args));
  }

  @Test
  void aPermissionOfOneAgentDoesNotCoverTheOthers() {
    // Tools an agent does not list are refused before anything runs.
    assertThrows(
        SecurityException.class,
        () -> as("weather-daily", "shell_exec", "{\"command\":\"python\"}"));
    assertThrows(
        SecurityException.class,
        () -> as("github-daily", "http_get", "{\"url\":\"http://127.0.0.1:1/\"}"));
    assertThrows(
        SecurityException.class,
        () -> as("daily-tech-digest", "shell_exec", "{\"command\":\"python\"}"));

    // Tools they do list are still bounded by their own sandbox declaration.
    var readOutsideSkills =
        as(
            "daily-tech-digest",
            "file_read",
            "{\"path\":\"" + DemoSupport.slash(WS.allowed) + "/anything.txt\"}");
    assertTrue(
        readOutsideSkills.isDenied(),
        "only the skills directory is readable: " + readOutsideSkills);
    var otherHost = as("weather-daily", "http_get", "{\"url\":\"http://hn.algolia.com/\"}");
    assertTrue(otherHost.isDenied(), "the weather agent may not reach the news host: " + otherHost);
    var otherProgram =
        as("github-daily", "shell_exec", "{\"command\":\"git\",\"args\":[\"status\"]}");
    assertTrue(
        otherProgram.isDenied(), "the github agent may run only its script: " + otherProgram);
  }

  @Test
  void sessionsOfTheThreeAgentsAreSeparate() throws Exception {
    requirePython();
    DemoSupport.runNow(mvc, WEATHER_TASK);
    DemoSupport.runNow(mvc, TECH_TASK);
    DemoSupport.runNow(mvc, GITHUB_TASK);

    for (String agent : List.of("weather-daily", "daily-tech-digest", "github-daily")) {
      String session = "scheduler:scheduler:" + agent;
      Integer foreign =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM tool_invocations WHERE session_id = ? AND tool_name NOT IN (SELECT"
                  + " tool_name FROM tool_invocations WHERE session_id = ?)",
              Integer.class,
              session,
              session);
      assertEquals(0, foreign);
    }
    assertEquals(
        0,
        count(
            "tool_invocations WHERE session_id = 'scheduler:scheduler:weather-daily' AND tool_name IN ('shell_exec','file_read')"));
    assertEquals(
        0,
        count(
            "tool_invocations WHERE session_id = 'scheduler:scheduler:github-daily' AND tool_name IN ('http_get','file_read')"));
    assertEquals(
        0,
        count(
            "tool_invocations WHERE session_id = 'scheduler:scheduler:daily-tech-digest' AND tool_name = 'shell_exec'"));
  }
}
