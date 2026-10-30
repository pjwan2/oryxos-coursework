package io.oryxos;

import static io.oryxos.LocalTestServers.Decision.say;
import static io.oryxos.LocalTestServers.systemContent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.LocalTestServers.Decision;
import io.oryxos.LocalTestServers.FakeModelServer;
import io.oryxos.core.agent.AgentService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Lesson 30 on the whole system, only the model scripted. Create, read, change, run through the
 * console session, give an agent a private memory, archive it, restart: every step goes through the
 * REST API and the real registry, scheduler, sandbox, memory files and SQLite.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminAgentFlowTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final E2eWorkspace WS = new E2eWorkspace("oryxos-e2e-admin");
  private static final AtomicReference<String> DRAFT = new AtomicReference<>();
  private static final List<String> SYSTEM_PROMPTS = new ArrayList<>();
  private static final FakeModelServer MODEL = new FakeModelServer(AdminAgentFlowTest::brain, 0);

  /** Plays the model: a drafted AGENT.md for generation requests, a plain echo otherwise. */
  private static Decision brain(JsonNode request) {
    String system = systemContent(request);
    SYSTEM_PROMPTS.add(system);
    if (system.contains("You write the definition of an AI agent")) {
      return say(DRAFT.get());
    }
    return say("model says hi");
  }

  static {
    WS.profile("legacy-one", "mock", List.of("save_memory", "recall_memory"), null, null);
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry registry) {
    WS.register(registry, MODEL.baseUrl());
    registry.add("oryxos.archive-directory", () -> E2eWorkspace.slash(WS.root.resolve("archive")));
    registry.add("oryxos.agents-watch", () -> "false");
  }

  @AfterAll
  static void stop() {
    MODEL.close();
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired Environment environment;

  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("springAiAgentService")
  AgentService agent;

  private JsonNode send(MockHttpServletRequestBuilder request, int expected) throws Exception {
    var result = mvc.perform(request).andReturn().getResponse();
    assertEquals(expected, result.getStatus(), result.getContentAsString());
    return JSON.readTree(result.getContentAsString());
  }

  private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder r, String body) {
    return r.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static String q(String text) {
    try {
      return JSON.writeValueAsString(text);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String agentMd(String description, String tools, String body) {
    return "---\ndescription: "
        + description
        + "\nprovider: {name: mock, model: mock-echo}\ntools: "
        + tools
        + "\n---\n"
        + body
        + "\n";
  }

  @Test
  @Order(1)
  void theApplicationListensOnTheLoopbackAddressOnly() {
    assertEquals("127.0.0.1", environment.getProperty("server.address"));
  }

  @Test
  @Order(2)
  void createThenReadBackTheAgentFromTheTemplate() throws Exception {
    JsonNode created =
        send(
            json(post("/api/v1/agents"), "{\"name\":\"alpha\",\"description\":\"First agent\"}"),
            201);
    assertEquals("alpha", created.at("/data/name").asText());
    assertEquals("mock", created.at("/data/provider").asText());
    assertTrue(created.at("/data/privateMemory").asBoolean());
    assertTrue(Files.isRegularFile(WS.agents.resolve("alpha/AGENT.md")));

    JsonNode listing = send(get("/api/v1/agents"), 200);
    List<String> names = new ArrayList<>();
    listing.at("/data/agents").forEach(a -> names.add(a.get("name").asText()));
    assertTrue(names.containsAll(List.of("alpha", "legacy-one")), names.toString());
    assertEquals("legacy-yaml", agentOf(listing, "legacy-one").get("origin").asText());
    assertFalse(agentOf(listing, "legacy-one").get("editable").asBoolean());

    send(json(post("/api/v1/agents"), "{\"name\":\"alpha\",\"description\":\"again\"}"), 409);
    send(json(post("/api/v1/agents"), "{\"name\":\"legacy-one\",\"description\":\"x\"}"), 409);
    send(json(post("/api/v1/agents"), "{\"name\":\"../evil\",\"description\":\"x\"}"), 400);
    send(get("/api/v1/agents/nobody"), 404);
  }

  private static JsonNode agentOf(JsonNode listing, String name) {
    for (JsonNode a : listing.at("/data/agents")) {
      if (name.equals(a.get("name").asText())) {
        return a;
      }
    }
    throw new AssertionError("no agent " + name);
  }

  @Test
  @Order(3)
  void updateChangesWhatRunsAndAnInvalidUpdateChangesNothing() throws Exception {
    String text =
        agentMd("Second version", "[save_memory, recall_memory]", "You are alpha. ALPHA-V2");
    JsonNode updated =
        send(json(put("/api/v1/agents/alpha"), "{\"agentMd\":" + q(text) + "}"), 200);
    assertEquals("Second version", updated.at("/data/description").asText());
    assertEquals("save_memory", updated.at("/data/tools/0").asText());

    String before = Files.readString(WS.agents.resolve("alpha/AGENT.md"));
    JsonNode refused =
        send(
            json(
                put("/api/v1/agents/alpha"),
                "{\"agentMd\":" + q(agentMd("bad", "[teleport]", "x")) + "}"),
            400);
    assertTrue(refused.get("message").asText().contains("tool 'teleport' is not registered"));
    assertEquals(before, Files.readString(WS.agents.resolve("alpha/AGENT.md")));
    assertEquals(
        "Second version", send(get("/api/v1/agents/alpha"), 200).at("/data/description").asText());

    send(json(put("/api/v1/agents/legacy-one"), "{\"agentMd\":" + q(text) + "}"), 409);
  }

  @Test
  @Order(4)
  void theConsoleSessionIsOneStableConversationThatSurvivesNewRequests() throws Exception {
    JsonNode empty = send(get("/api/v1/agents/alpha/session"), 200);
    assertFalse(empty.at("/data/exists").asBoolean());
    assertEquals("admin:console:alpha", empty.at("/data/sessionId").asText());

    JsonNode reply =
        send(json(post("/api/v1/agents/alpha/session/messages"), "{\"message\":\"hello\"}"), 200);
    assertEquals("Mock response: hello", reply.at("/data/reply").asText());
    send(json(post("/api/v1/agents/alpha/session/messages"), "{\"message\":\"again\"}"), 200);

    JsonNode session = send(get("/api/v1/agents/alpha/session"), 200);
    assertTrue(session.at("/data/exists").asBoolean());
    assertEquals(4, session.at("/data/totalMessages").asInt());
    assertEquals("hello", session.at("/data/messages/0/content").asText());
    assertEquals("Mock response: again", session.at("/data/messages/3/content").asText());
    assertEquals(
        2,
        E2eWorkspace.count(jdbc, "llm_calls", "admin:console:alpha"),
        "the console used the normal audited provider path");
  }

  @Test
  @Order(5)
  void eachAgentHasItsOwnLongTermMemoryAndALegacyOneStillSharesTheGlobalFile() throws Exception {
    send(json(post("/api/v1/agents"), "{\"name\":\"beta\",\"description\":\"Second agent\"}"), 201);
    send(
        json(
            put("/api/v1/agents/beta"),
            "{\"agentMd\":"
                + q(agentMd("Beta", "[save_memory, recall_memory]", "You are beta."))
                + "}"),
        200);

    agent.process(
        "api",
        "u",
        "alpha",
        "tool:save_memory {\"content\":\"alpha loves espresso\",\"scope\":\"CORE\"}");
    agent.process(
        "api", "u", "beta", "tool:save_memory {\"content\":\"beta loves tea\",\"scope\":\"CORE\"}");
    agent.process(
        "api",
        "u",
        "legacy-one",
        "tool:save_memory {\"content\":\"legacy shared fact\",\"scope\":\"CORE\"}");

    String alphaFile = Files.readString(WS.agents.resolve("alpha/MEMORY.md"));
    String betaFile = Files.readString(WS.agents.resolve("beta/MEMORY.md"));
    assertTrue(alphaFile.contains("alpha loves espresso") && !alphaFile.contains("beta loves tea"));
    assertTrue(betaFile.contains("beta loves tea") && !betaFile.contains("alpha loves espresso"));
    String global = Files.readString(WS.memoryFile);
    assertTrue(global.contains("legacy shared fact"));
    assertFalse(global.contains("espresso") || global.contains("beta loves tea"));

    // B asking for A's fact finds nothing; A finds its own in a brand-new session.
    String b =
        agent.process(
            "api", "other-session", "beta", "tool:recall_memory {\"keyword\":\"espresso\"}");
    assertTrue(b.contains("No matching archived memories"), b);

    JsonNode alphaMemory = send(get("/api/v1/agents/alpha/memory"), 200);
    assertTrue(alphaMemory.at("/data/privateMemory").asBoolean());
    assertEquals("alpha loves espresso", alphaMemory.at("/data/core/0/content").asText());
    JsonNode betaMemory = send(get("/api/v1/agents/beta/memory"), 200);
    assertEquals("beta loves tea", betaMemory.at("/data/core/0/content").asText());
    JsonNode legacyMemory = send(get("/api/v1/agents/legacy-one/memory"), 200);
    assertTrue(legacyMemory.at("/data/shared").asBoolean());
    assertEquals("legacy shared fact", legacyMemory.at("/data/core/0/content").asText());
  }

  @Test
  @Order(6)
  void generateOnlyProposesAndSavingNeedsASeparateConfirmation() throws Exception {
    // Drafts come from the agent's own model: point beta at the (scripted) OpenAI-compatible one.
    String viaFake =
        "---\ndescription: Beta\nprovider: {name: "
            + E2eWorkspace.FAKE_PROVIDER
            + ", model: fake-model}\ntools: [save_memory, recall_memory]\n---\nYou are beta.\n";
    send(json(put("/api/v1/agents/beta"), "{\"agentMd\":" + q(viaFake) + "}"), 200);
    DRAFT.set(
        "<<<FILE AGENT.md>>>\n---\nname: beta\ndescription: Drafted by the model\n"
            + "provider: {name: "
            + E2eWorkspace.FAKE_PROVIDER
            + ", model: fake-model}\n"
            + "tools: [save_memory]\n---\nDrafted instructions DRAFT-V1\n<<<END>>>\n"
            + "<<<FILE REFERENCE.md>>>\nDrafted reference\n<<<END>>>");
    String before = Files.readString(WS.agents.resolve("beta/AGENT.md"));
    int llmBefore = count("llm_calls WHERE session_id = 'admin:generator:beta'");

    JsonNode draft =
        send(
            json(post("/api/v1/agents/beta/generate-files"), "{\"description\":\"a tea agent\"}"),
            200);

    assertTrue(draft.at("/data/valid").asBoolean(), draft.toString());
    assertEquals(2, draft.at("/data/files").size());
    assertEquals(before, Files.readString(WS.agents.resolve("beta/AGENT.md")), "nothing was saved");
    assertFalse(Files.exists(WS.agents.resolve("beta/REFERENCE.md")));
    assertEquals("Beta", send(get("/api/v1/agents/beta"), 200).at("/data/description").asText());
    assertEquals(
        llmBefore + 1,
        count("llm_calls WHERE session_id = 'admin:generator:beta'"),
        "the draft call is audited like any other model call");
    assertFalse(
        draft.toString().contains("You write the definition"), "the prompt is not returned");
    assertFalse(draft.toString().contains(E2eWorkspace.DUMMY_KEY));

    JsonNode saved =
        send(
            json(
                post("/api/v1/agents/beta/files"),
                "{\"files\":" + draft.at("/data/files").toString() + "}"),
            200);
    assertEquals("Drafted by the model", saved.at("/data/description").asText());
    assertEquals("Drafted reference\n", Files.readString(WS.agents.resolve("beta/REFERENCE.md")));
    assertEquals(
        E2eWorkspace.FAKE_PROVIDER,
        send(get("/api/v1/agents/beta"), 200).at("/data/provider").asText());
  }

  @Test
  @Order(7)
  void anInvalidDraftIsReportedReadablyAndCannotBeSavedAsIs() throws Exception {
    DRAFT.set("<<<FILE AGENT.md>>>\n---\ndescription: [broken\n---\nBody\n<<<END>>>");

    JsonNode draft =
        send(json(post("/api/v1/agents/beta/generate-files"), "{\"description\":\"x\"}"), 200);

    assertFalse(draft.at("/data/valid").asBoolean());
    assertTrue(draft.at("/data/problems/0").asText().contains("not valid YAML"));
    send(
        json(
            post("/api/v1/agents/beta/files"),
            "{\"files\":" + draft.at("/data/files").toString() + "}"),
        400);
    assertEquals(
        "Drafted by the model",
        send(get("/api/v1/agents/beta"), 200).at("/data/description").asText(),
        "the earlier valid agent is untouched");
  }

  @Test
  @Order(8)
  void theWorkspaceApiReadsAndSavesFilesAndRefusesTheRest() throws Exception {
    JsonNode tree = send(get("/api/v1/workspace/tree"), 200);
    List<String> paths = new ArrayList<>();
    tree.at("/data").forEach(e -> paths.add(e.get("path").asText()));
    assertTrue(paths.contains("alpha/AGENT.md"));
    assertTrue(paths.contains("beta/REFERENCE.md"));

    send(get("/api/v1/workspace/file").param("path", "alpha/AGENT.md"), 200);
    send(get("/api/v1/workspace/file").param("path", "../oryxos-e2e.db"), 400);
    send(get("/api/v1/workspace/file").param("path", "/etc/passwd"), 400);
    send(get("/api/v1/workspace/file").param("path", "alpha/MEMORY.md"), 200);
    send(
        json(
            post("/api/v1/workspace/file"), "{\"path\":\"alpha/MEMORY.md\",\"content\":\"wiped\"}"),
        400);

    String edited = agentMd("Edited via workspace", "[save_memory, recall_memory]", "ALPHA-V3");
    send(
        json(
            post("/api/v1/workspace/file"),
            "{\"path\":\"alpha/AGENT.md\",\"content\":" + q(edited) + "}"),
        200);
    assertEquals(
        "Edited via workspace",
        send(get("/api/v1/agents/alpha"), 200).at("/data/description").asText());
    JsonNode refused =
        send(
            json(
                post("/api/v1/workspace/file"),
                "{\"path\":\"alpha/AGENT.md\",\"content\":\"no frontmatter\"}"),
            400);
    assertTrue(refused.get("message").asText().contains("must start with"));
    assertEquals(
        "Edited via workspace",
        send(get("/api/v1/agents/alpha"), 200).at("/data/description").asText());
  }

  @Test
  @Order(9)
  void everyWriteRefusesACrossSiteBrowser() throws Exception {
    for (MockHttpServletRequestBuilder request :
        List.of(
            json(post("/api/v1/agents"), "{\"name\":\"evil\",\"description\":\"d\"}"),
            json(put("/api/v1/agents/alpha"), "{\"agentMd\":\"x\"}"),
            delete("/api/v1/agents/alpha"),
            json(post("/api/v1/agents/alpha/files"), "{\"files\":[]}"),
            json(post("/api/v1/workspace/file"), "{\"path\":\"alpha/a.md\",\"content\":\"x\"}"),
            json(post("/api/v1/agents/alpha/session/messages"), "{\"message\":\"x\"}"))) {
      send(request.header("Origin", "https://evil.example"), 403);
    }
    assertFalse(Files.exists(WS.agents.resolve("evil")));
    assertTrue(Files.exists(WS.agents.resolve("alpha/AGENT.md")));
  }

  @Test
  @Order(10)
  void archiveMovesTheDirectoryAndItsPrivateMemoryAndKeepsTheHistory() throws Exception {
    int sessionsBefore = count("sessions WHERE session_id = 'admin:console:alpha'");
    int llmBefore = count("llm_calls WHERE session_id = 'admin:console:alpha'");
    String memoryBefore = Files.readString(WS.agents.resolve("alpha/MEMORY.md"));

    JsonNode archived = send(delete("/api/v1/agents/alpha"), 200);

    String location = archived.at("/data/location").asText();
    assertTrue(location.startsWith("archive/alpha-"), location);
    assertFalse(Files.exists(WS.agents.resolve("alpha")));
    Path moved = WS.root.resolve(location);
    assertTrue(Files.isRegularFile(moved.resolve("AGENT.md")));
    assertEquals(
        memoryBefore, Files.readString(moved.resolve("MEMORY.md")), "memory moved with the agent");
    send(get("/api/v1/agents/alpha"), 404);
    send(json(post("/api/v1/agents/alpha/session/messages"), "{\"message\":\"x\"}"), 404);
    assertEquals(sessionsBefore, count("sessions WHERE session_id = 'admin:console:alpha'"));
    assertEquals(
        llmBefore,
        count("llm_calls WHERE session_id = 'admin:console:alpha'"),
        "audit history stays");
    assertTrue(
        Files.readString(WS.memoryFile).contains("legacy shared fact"),
        "the global memory file is untouched");
    send(delete("/api/v1/agents/legacy-one"), 409);
    send(delete("/api/v1/agents/alpha"), 404);
  }

  @Test
  @Order(11)
  void aRestartRecoversTheAgentsTheirMemoryAndTheArchive() throws Exception {
    List<String> args = new ArrayList<>();
    args.add("--logging.level.root=ERROR");
    args.add("--oryxos.agents-watch=false");
    args.add("--oryxos.archive-directory=" + E2eWorkspace.slash(WS.root.resolve("archive")));
    WS.properties(MODEL.baseUrl()).forEach((key, value) -> args.add("--" + key + "=" + value));

    try (ConfigurableApplicationContext second =
        new SpringApplicationBuilder(OryxOsApplication.class)
            .web(WebApplicationType.NONE)
            .bannerMode(org.springframework.boot.Banner.Mode.OFF)
            .run(args.toArray(String[]::new))) {
      var lifecycle = second.getBean(io.oryxos.core.profile.AgentManagement.class);
      List<String> names = lifecycle.list().stream().map(v -> v.name()).toList();
      assertTrue(names.contains("beta") && names.contains("legacy-one"), names.toString());
      assertFalse(names.contains("alpha"), "the archived agent does not come back");
      assertEquals("Drafted by the model", lifecycle.get("beta").description());

      AgentService again = second.getBean("springAiAgentService", AgentService.class);
      String recalled =
          again.process("api", "fresh-session", "beta", "tool:recall_memory {\"keyword\":\"tea\"}");
      assertNotNull(recalled);
      var memories = second.getBean(io.oryxos.core.memory.AgentMemories.class);
      assertEquals(
          List.of("beta loves tea"),
          memories.forAgent("beta").readCore().stream().map(e -> e.content()).toList());
      assertEquals(
          List.of("legacy shared fact"),
          memories.forAgent("legacy-one").readCore().stream().map(e -> e.content()).toList());
    }
  }

  private int count(String fromWhere) {
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + fromWhere, Integer.class);
    return n == null ? 0 : n;
  }
}
