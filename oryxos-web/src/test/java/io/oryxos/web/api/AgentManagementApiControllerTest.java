package io.oryxos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.agent.AgentMessage;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.AgentSession;
import io.oryxos.core.agent.AgentSessionStore;
import io.oryxos.core.agent.ModelUnavailableException;
import io.oryxos.core.agent.ProfileNotFoundException;
import io.oryxos.core.memory.AgentMemories;
import io.oryxos.core.memory.LongTermMemoryStore;
import io.oryxos.core.memory.MemoryEntry;
import io.oryxos.core.memory.MemoryIsolationUnsupportedException;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.profile.AgentConflictException;
import io.oryxos.core.profile.AgentDefinitionException;
import io.oryxos.core.profile.AgentDraft;
import io.oryxos.core.profile.AgentDrafting;
import io.oryxos.core.profile.AgentFile;
import io.oryxos.core.profile.AgentManagement;
import io.oryxos.core.profile.AgentView;
import io.oryxos.core.profile.ArchiveResult;
import io.oryxos.core.profile.DraftUnavailableException;
import io.oryxos.core.profile.ProfileCatalog;
import io.oryxos.core.workspace.WorkspacePathException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** HTTP contract of the agent management API: shapes, status codes and the local-only rule. */
class AgentManagementApiControllerTest {
  private final AgentManagement agents = mock(AgentManagement.class);
  private final AgentDrafting drafting = mock(AgentDrafting.class);
  private final AgentService agentService = mock(AgentService.class);
  private final AgentSessionStore sessions = mock(AgentSessionStore.class);
  private final ProfileCatalog profiles = mock(ProfileCatalog.class);
  private final AgentMemories memories = mock(AgentMemories.class);
  private MockMvc mvc;

  private static AgentView view(String name) {
    return new AgentView(
        name,
        "d",
        AgentView.ORIGIN_AGENT_MD,
        true,
        "mock",
        "mock-echo",
        0.2,
        List.of("file_read"),
        List.of(),
        List.of(),
        List.of("REFERENCE.md"),
        true,
        "Body",
        null);
  }

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(
                new AgentManagementApiController(
                    agents,
                    drafting,
                    new AgentInvoker(agentService),
                    sessions,
                    profiles,
                    memories,
                    "markdown"))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private static MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, String body) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  // ---- CRUD ------------------------------------------------------------------------------

  @Test
  void createReturns201WithTheNewAgent() throws Exception {
    when(agents.create("helper", "Does things")).thenReturn(view("helper"));

    mvc.perform(
            json(post("/api/v1/agents"), "{\"name\":\"helper\",\"description\":\"Does things\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.name").value("helper"))
        .andExpect(jsonPath("$.data.privateMemory").value(true))
        .andExpect(jsonPath("$.data.tools[0]").value("file_read"));
  }

  @Test
  void listReturnsTheAgentsAndTheDirectoriesThatWereNotApplied() throws Exception {
    when(agents.list()).thenReturn(List.of(view("a"), view("b")));
    when(agents.problems()).thenReturn(Map.of("broken", "agents/broken/AGENT.md: not found"));

    mvc.perform(get("/api/v1/agents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.agents.length()").value(2))
        .andExpect(jsonPath("$.data.problems.broken").value("agents/broken/AGENT.md: not found"));
  }

  @Test
  void getUpdateAndArchiveDelegate() throws Exception {
    when(agents.get("helper")).thenReturn(view("helper"));
    when(agents.update("helper", "TEXT")).thenReturn(view("helper"));
    when(agents.archive("helper")).thenReturn(new ArchiveResult("helper", "archive/helper-1"));

    mvc.perform(get("/api/v1/agents/helper")).andExpect(status().isOk());
    mvc.perform(json(put("/api/v1/agents/helper"), "{\"agentMd\":\"TEXT\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.instructions").value("Body"));
    mvc.perform(delete("/api/v1/agents/helper"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.location").value("archive/helper-1"));
  }

  @Test
  void validationConflictAndUnknownMapToTheirStatusCodes() throws Exception {
    when(agents.create(anyString(), anyString()))
        .thenThrow(new AgentDefinitionException("the agent name must match [A-Za-z0-9_-]{1,64}"));
    mvc.perform(json(post("/api/v1/agents"), "{\"name\":\"a b\",\"description\":\"d\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("the agent name must match [A-Za-z0-9_-]{1,64}"));

    when(agents.update(anyString(), anyString()))
        .thenThrow(new AgentConflictException("read-only legacy"));
    mvc.perform(json(put("/api/v1/agents/old"), "{\"agentMd\":\"x\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("read-only legacy"));

    when(agents.get("nobody")).thenThrow(new ProfileNotFoundException("nobody"));
    mvc.perform(get("/api/v1/agents/nobody")).andExpect(status().isNotFound());
  }

  @Test
  void aBodyWithoutTheRequiredFieldIs400AndNothingIsCalled() throws Exception {
    mvc.perform(json(put("/api/v1/agents/helper"), "{}")).andExpect(status().isBadRequest());
    mvc.perform(json(post("/api/v1/agents/helper/generate-files"), "null"))
        .andExpect(status().isBadRequest());
    mvc.perform(json(post("/api/v1/agents/helper/files"), "{broken"))
        .andExpect(status().isBadRequest());
    verify(agents, never()).update(any(), any());
    verifyNoInteractions(drafting);
  }

  // ---- fixed console session ---------------------------------------------------------------

  @Test
  void theConsoleSessionIsOneStableIdPerAgentAndRunsThroughTheSharedAgentService()
      throws Exception {
    when(profiles.get("helper"))
        .thenReturn(io.oryxos.core.profile.AgentProfile.example("mock", "m"));
    when(agentService.process("admin", "console", "helper", "hello")).thenReturn("hi there");

    mvc.perform(json(post("/api/v1/agents/helper/session/messages"), "{\"message\":\"hello\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value("admin:console:helper"))
        .andExpect(jsonPath("$.data.reply").value("hi there"));
    verify(agentService).process("admin", "console", "helper", "hello");
  }

  @Test
  void readingTheConsoleSessionShowsTheStoredMessagesOrAnEmptyOne() throws Exception {
    when(profiles.get("helper"))
        .thenReturn(io.oryxos.core.profile.AgentProfile.example("mock", "m"));
    when(sessions.find("admin:console:helper")).thenReturn(Optional.empty());
    mvc.perform(get("/api/v1/agents/helper/session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.exists").value(false))
        .andExpect(jsonPath("$.data.sessionId").value("admin:console:helper"))
        .andExpect(jsonPath("$.data.messages.length()").value(0));

    AgentSession stored = new AgentSession("admin:console:helper", "helper");
    stored.append(new AgentMessage(AgentMessage.Role.USER, "hello"));
    stored.append(new AgentMessage(AgentMessage.Role.ASSISTANT, "hi"));
    when(sessions.find("admin:console:helper")).thenReturn(Optional.of(stored));
    mvc.perform(get("/api/v1/agents/helper/session"))
        .andExpect(jsonPath("$.data.exists").value(true))
        .andExpect(jsonPath("$.data.totalMessages").value(2))
        .andExpect(jsonPath("$.data.messages[1].content").value("hi"));
  }

  @Test
  void theConsoleSessionOfAnUnknownAgentIs404AndABadMessageNeverReachesTheAgent() throws Exception {
    when(profiles.get("nobody")).thenThrow(new ProfileNotFoundException("nobody"));
    mvc.perform(get("/api/v1/agents/nobody/session")).andExpect(status().isNotFound());
    mvc.perform(json(post("/api/v1/agents/nobody/session/messages"), "{\"message\":\"x\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(json(post("/api/v1/agents/helper/session/messages"), "{\"message\":\" \"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(agentService);
  }

  @Test
  void aProviderFailureInTheConsoleSessionIs503WithoutProviderText() throws Exception {
    when(profiles.get("helper"))
        .thenReturn(io.oryxos.core.profile.AgentProfile.example("mock", "m"));
    when(agentService.process(any(), any(), any(), any()))
        .thenThrow(new ModelUnavailableException("p"));

    mvc.perform(json(post("/api/v1/agents/helper/session/messages"), "{\"message\":\"x\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.message").value("Model provider unavailable"));
  }

  // ---- draft and save ----------------------------------------------------------------------

  @Test
  void generateReturnsADraftAndSavesNothing() throws Exception {
    when(drafting.generate("helper", "reconcile"))
        .thenReturn(new AgentDraft(List.of(new AgentFile("AGENT.md", "TEXT")), true, List.of()));

    mvc.perform(
            json(post("/api/v1/agents/helper/generate-files"), "{\"description\":\"reconcile\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.valid").value(true))
        .andExpect(jsonPath("$.data.files[0].path").value("AGENT.md"));
    verify(agents, never()).saveFiles(any(), any());
  }

  @Test
  void aMissingProviderIsAReadable409() throws Exception {
    when(drafting.generate(anyString(), anyString()))
        .thenThrow(new DraftUnavailableException("No usable provider/model is configured"));

    mvc.perform(json(post("/api/v1/agents/helper/generate-files"), "{\"description\":\"x\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("No usable provider/model is configured"));
  }

  @Test
  void saveFilesPassesTheConfirmedFilesAndReportsWorkspaceRefusals() throws Exception {
    when(agents.saveFiles("helper", List.of(new AgentFile("AGENT.md", "T"))))
        .thenReturn(view("helper"));
    mvc.perform(
            json(
                post("/api/v1/agents/helper/files"),
                "{\"files\":[{\"path\":\"AGENT.md\",\"content\":\"T\"}]}"))
        .andExpect(status().isOk());

    when(agents.saveFiles(anyString(), any()))
        .thenThrow(new WorkspacePathException("Workspace path refused: links are not followed"));
    mvc.perform(
            json(
                post("/api/v1/agents/helper/files"),
                "{\"files\":[{\"path\":\"x/../y.md\",\"content\":\"T\"}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Workspace path refused: links are not followed"));
  }

  // ---- memory ------------------------------------------------------------------------------

  @Test
  void theMemoryTabShowsAPrivateMemoryOrSaysItIsTheSharedOne() throws Exception {
    LongTermMemoryStore store = mock(LongTermMemoryStore.class);
    when(store.readCore())
        .thenReturn(
            List.of(new MemoryEntry(MemoryScope.CORE, "likes tea", LocalDate.of(2026, 3, 1))));
    when(store.readArchival(100)).thenReturn(List.of());
    when(memories.forAgent("helper")).thenReturn(store);
    when(memories.isolated("helper")).thenReturn(true);
    when(memories.isolated("old")).thenReturn(false);
    when(memories.forAgent("old")).thenReturn(store);

    mvc.perform(get("/api/v1/agents/helper/memory"))
        .andExpect(jsonPath("$.data.privateMemory").value(true))
        .andExpect(jsonPath("$.data.shared").value(false))
        .andExpect(jsonPath("$.data.core[0].content").value("likes tea"));
    mvc.perform(get("/api/v1/agents/old/memory"))
        .andExpect(jsonPath("$.data.privateMemory").value(false))
        .andExpect(jsonPath("$.data.shared").value(true));
  }

  @Test
  void aBackendThatCannotIsolateIsAClear409NotASilentShare() throws Exception {
    when(memories.isolated("helper")).thenReturn(true);
    when(memories.forAgent("helper"))
        .thenThrow(new MemoryIsolationUnsupportedException("mem0 cannot isolate"));

    mvc.perform(get("/api/v1/agents/helper/memory"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("mem0 cannot isolate"));
  }

  // ---- local access ---------------------------------------------------------------------

  /** One fresh request per use: a builder cannot be reused once headers were added. */
  private static List<Supplier<MockHttpServletRequestBuilder>> everyEndpoint() {
    return List.of(
        () -> json(post("/api/v1/agents"), "{\"name\":\"a\",\"description\":\"d\"}"),
        () -> get("/api/v1/agents"),
        () -> get("/api/v1/agents/helper"),
        () -> json(put("/api/v1/agents/helper"), "{\"agentMd\":\"x\"}"),
        () -> delete("/api/v1/agents/helper"),
        () -> get("/api/v1/agents/helper/session"),
        () -> json(post("/api/v1/agents/helper/session/messages"), "{\"message\":\"x\"}"),
        () -> json(post("/api/v1/agents/helper/generate-files"), "{\"description\":\"x\"}"),
        () -> json(post("/api/v1/agents/helper/files"), "{\"files\":[]}"),
        () -> get("/api/v1/agents/helper/memory"));
  }

  @Test
  void everyEndpointRefusesANonLocalCallerACrossSiteBrowserAndARebindingHost() throws Exception {
    List<UnaryOperator<MockHttpServletRequestBuilder>> attacks =
        List.of(
            r ->
                r.with(
                    req -> {
                      req.setRemoteAddr("198.51.100.4");
                      return req;
                    }),
            r -> r.header("Origin", "https://evil.example"),
            r -> r.header("Host", "evil.example"),
            r -> r.header("X-Forwarded-For", "203.0.113.9"));
    int checked = 0;
    for (Supplier<MockHttpServletRequestBuilder> endpoint : everyEndpoint()) {
      for (UnaryOperator<MockHttpServletRequestBuilder> attack : attacks) {
        mvc.perform(attack.apply(endpoint.get())).andExpect(status().isForbidden());
        checked++;
      }
    }
    assertEquals(40, checked);
    verifyNoInteractions(agents, drafting, agentService, sessions, memories);
  }

  @Test
  void aBrowserOnTheSameMachineIsAccepted() throws Exception {
    when(agents.list()).thenReturn(List.of());
    when(agents.problems()).thenReturn(Map.of());

    mvc.perform(
            get("/api/v1/agents")
                .header("Origin", "http://localhost:8080")
                .header("Host", "localhost:8080"))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/v1/agents")
                .header("Origin", "http://127.0.0.1:5173")
                .header("Host", "127.0.0.1:8080"))
        .andExpect(status().isOk());
  }
}
