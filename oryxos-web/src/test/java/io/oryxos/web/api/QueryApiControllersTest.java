package io.oryxos.web.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.oryxos.core.agent.AgentSessionStore;
import io.oryxos.core.agent.ToolCallHistory;
import io.oryxos.core.agent.ToolCallRecord;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.memory.LongTermMemoryStore;
import io.oryxos.core.memory.MemoryEntry;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryStoreException;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.profile.ProfileCatalog;
import io.oryxos.core.schedule.ScheduleService;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolContext;
import io.oryxos.core.tool.ToolRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Profile, memory, tool and system queries: shape, limits, redaction and the 503 mapping. */
class QueryApiControllersTest {
  private static final RequestPostProcessor REMOTE =
      request -> {
        request.setRemoteAddr("203.0.113.9");
        return request;
      };

  /** Same JSON settings as Spring Boot: dates as ISO strings, not arrays of numbers. */
  private static MockMvc mvc(Object... controllers) {
    return MockMvcBuilders.standaloneSetup(controllers)
        .setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(
            new MappingJackson2HttpMessageConverter(
                Jackson2ObjectMapperBuilder.json()
                    .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build()))
        .build();
  }

  // ---- profiles -------------------------------------------------------------------------

  @Test
  void profilesShowDescriptiveFieldsAndNeverNotifyTargetsOrSandboxPaths() throws Exception {
    AgentProfile profile =
        new AgentProfile(
            "alerts",
            "Sends alerts",
            Map.of(),
            new AgentProfile.ProviderSettings("mock", "m", 0.5),
            List.of("notify", "http_get"),
            List.of("triage"),
            List.of("github"),
            List.of(),
            List.of(Map.of("type", "webhook", "url", "https://hooks.example.org/SECRET-TOKEN")),
            List.of(Map.of("id", "a"), Map.of("id", "b")),
            Map.of(),
            Map.of("sandbox", Map.of("read_roots", List.of("C:/private/dir"))));
    ProfileCatalog catalog = mock(ProfileCatalog.class);
    when(catalog.list()).thenReturn(List.of(profile));

    mvc(new ProfileApiController(catalog))
        .perform(get("/api/v1/profiles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].name").value("alerts"))
        .andExpect(jsonPath("$.data[0].provider.name").value("mock"))
        .andExpect(jsonPath("$.data[0].provider.temperature").value(0.5))
        .andExpect(jsonPath("$.data[0].tools[1]").value("http_get"))
        .andExpect(jsonPath("$.data[0].mcpServers[0]").value("github"))
        .andExpect(jsonPath("$.data[0].scheduleCount").value(2))
        .andExpect(jsonPath("$.data[0].sandboxDeclared").value(true))
        .andExpect(content().string(not(containsString("SECRET-TOKEN"))))
        .andExpect(content().string(not(containsString("private"))));
  }

  @Test
  void noProfilesIsAnEmptyListNotAnError() throws Exception {
    ProfileCatalog catalog = mock(ProfileCatalog.class);
    when(catalog.list()).thenReturn(List.of());
    mvc(new ProfileApiController(catalog))
        .perform(get("/api/v1/profiles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  // ---- memory ---------------------------------------------------------------------------

  @Test
  void memoryShowsBackendCoreAndRecentArchival() throws Exception {
    LongTermMemoryStore store = mock(LongTermMemoryStore.class);
    when(store.readCore())
        .thenReturn(
            List.of(new MemoryEntry(MemoryScope.CORE, "likes tea", LocalDate.of(2026, 3, 1))));
    when(store.readArchival(100))
        .thenReturn(List.of(new MemoryEntry(MemoryScope.ARCHIVAL, "met bob", null)));
    mvc(new MemoryApiController(store, " sqlite "))
        .perform(get("/api/v1/memory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.backend").value("sqlite"))
        .andExpect(jsonPath("$.data.core[0].content").value("likes tea"))
        .andExpect(jsonPath("$.data.core[0].savedOn").value("2026-03-01"))
        .andExpect(jsonPath("$.data.archival[0].scope").value("ARCHIVAL"))
        .andExpect(jsonPath("$.data.archivalLimit").value(100));
  }

  @Test
  void memoryLimitIsClampedAndABackendFailureIs503WithoutDetails() throws Exception {
    LongTermMemoryStore store = mock(LongTermMemoryStore.class);
    when(store.readCore()).thenReturn(List.of());
    when(store.readArchival(anyInt())).thenReturn(List.of());
    MockMvc mvc = mvc(new MemoryApiController(store, "markdown"));
    mvc.perform(get("/api/v1/memory?limit=999999")).andExpect(status().isOk());
    verify(store).readArchival(500);

    when(store.readCore()).thenThrow(new MemoryStoreException("mem0 at http://x?api_key=SECRET"));
    mvc.perform(get("/api/v1/memory"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.message").value("Memory backend unavailable"))
        .andExpect(content().string(not(containsString("SECRET"))));
  }

  @Test
  void memoryIsLocalOnly() throws Exception {
    LongTermMemoryStore store = mock(LongTermMemoryStore.class);
    mvc(new MemoryApiController(store, "markdown"))
        .perform(get("/api/v1/memory").with(REMOTE))
        .andExpect(status().isForbidden());
    verify(store, never()).readCore();
  }

  // ---- tools ----------------------------------------------------------------------------

  private static OryxTool tool(String name, String schema) {
    return new OryxTool() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getDescription() {
        return "Does " + name;
      }

      @Override
      public String getInputSchema() {
        return schema;
      }

      @Override
      public ToolOutput execute(String argumentsJson, ToolContext context) {
        return ToolOutput.ok("");
      }
    };
  }

  @Test
  void toolsListNameDescriptionAndTheSchemaAsAnObject() throws Exception {
    ToolRegistry registry =
        new ToolRegistry(
            List.of(
                tool(
                    "zeta", "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}}}"),
                tool("alpha", "not json")));
    mvc(new ToolApiController(registry, mock(ToolCallHistory.class), new ObjectMapper()))
        .perform(get("/api/v1/tools"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].name").value("alpha"))
        .andExpect(jsonPath("$.data[0].inputSchema").value("not json"))
        .andExpect(jsonPath("$.data[1].name").value("zeta"))
        .andExpect(jsonPath("$.data[1].description").value("Does zeta"))
        .andExpect(jsonPath("$.data[1].inputSchema.properties.url.type").value("string"));
  }

  @Test
  void toolCallsFilterByWindowAndClampTheLimit() throws Exception {
    ToolCallHistory history = mock(ToolCallHistory.class);
    Instant at = Instant.parse("2026-03-01T10:00:00Z");
    when(history.find(any(), any(), any(), anyInt()))
        .thenReturn(
            List.of(
                new ToolCallRecord(
                    9,
                    "scheduler:scheduler:ops",
                    "file_write",
                    false,
                    "SandboxDenied",
                    "Path outside allowed WRITE roots",
                    3,
                    at)));
    MockMvc mvc =
        mvc(new ToolApiController(new ToolRegistry(List.of()), history, new ObjectMapper()));

    mvc.perform(
            get("/api/v1/tool-calls")
                .param("sessionId", "scheduler:scheduler:ops")
                .param("since", "2026-03-01T09:59:59Z")
                .param("until", "2026-03-01T10:00:05Z")
                .param("limit", "5000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].toolName").value("file_write"))
        .andExpect(jsonPath("$.data[0].success").value(false))
        .andExpect(jsonPath("$.data[0].errorCategory").value("SandboxDenied"))
        .andExpect(jsonPath("$.data[0].errorMessage").value("Path outside allowed WRITE roots"));
    verify(history)
        .find(
            eq("scheduler:scheduler:ops"),
            eq(Instant.parse("2026-03-01T09:59:59Z")),
            eq(Instant.parse("2026-03-01T10:00:05Z")),
            eq(200));

    mvc.perform(get("/api/v1/tool-calls")).andExpect(status().isOk());
    verify(history).find(isNull(), isNull(), isNull(), eq(50));
  }

  @Test
  void toolCallsRejectABadSessionIdOrTimestampAndRefuseRemoteCallers() throws Exception {
    ToolCallHistory history = mock(ToolCallHistory.class);
    MockMvc mvc =
        mvc(new ToolApiController(new ToolRegistry(List.of()), history, new ObjectMapper()));
    mvc.perform(get("/api/v1/tool-calls?sessionId=not-valid")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/tool-calls?since=yesterday")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/tool-calls").with(REMOTE)).andExpect(status().isForbidden());
    verify(history, never()).find(any(), any(), any(), anyInt());
  }

  // ---- system ---------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static SystemApiController system(AgentSessionStore store, boolean schedulerOn) {
    ProfileCatalog catalog = mock(ProfileCatalog.class);
    when(catalog.list()).thenReturn(List.of(AgentProfile.example("mock", "m")));
    ScheduleService schedules = mock(ScheduleService.class);
    when(schedules.list()).thenReturn(List.of());
    ObjectProvider<org.springframework.boot.info.BuildProperties> none = mock(ObjectProvider.class);
    return new SystemApiController(
        store,
        catalog,
        new ToolRegistry(List.of(tool("only", "{}"))),
        schedules,
        none,
        "markdown",
        schedulerOn,
        "127.0.0.1");
  }

  @Test
  void healthIsOkWhenTheDatabaseAnswers() throws Exception {
    AgentSessionStore store = mock(AgentSessionStore.class);
    when(store.count()).thenReturn(3L);
    mvc(system(store, true))
        .perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.status").value("ok"))
        .andExpect(jsonPath("$.data.checks.database").value("ok"));
  }

  @Test
  void healthIs503WithoutDetailsWhenTheDatabaseIsDown() throws Exception {
    AgentSessionStore store = mock(AgentSessionStore.class);
    when(store.count()).thenThrow(new IllegalStateException("jdbc:sqlite:/data/prod.db is locked"));
    mvc(system(store, true))
        .perform(get("/api/v1/health"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(503))
        .andExpect(jsonPath("$.data.status").value("down"))
        .andExpect(jsonPath("$.data.checks.database").value("down"))
        .andExpect(content().string(not(containsString("jdbc"))));
  }

  @Test
  void infoReportsCountsAndSwitchesButNoConfigurationValues() throws Exception {
    AgentSessionStore store = mock(AgentSessionStore.class);
    when(store.count()).thenReturn(7L);
    mvc(system(store, false))
        .perform(get("/api/v1/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.application").value("oryxos"))
        .andExpect(jsonPath("$.data.stage").value("b4"))
        .andExpect(jsonPath("$.data.version").value("dev"))
        .andExpect(jsonPath("$.data.bindAddress").value("127.0.0.1"))
        .andExpect(jsonPath("$.data.database").value("sqlite"))
        .andExpect(jsonPath("$.data.memoryBackend").value("markdown"))
        .andExpect(jsonPath("$.data.schedulerEnabled").value(false))
        .andExpect(jsonPath("$.data.profiles").value(1))
        .andExpect(jsonPath("$.data.tools").value(1))
        .andExpect(jsonPath("$.data.sessions").value(7))
        .andExpect(jsonPath("$.data.authentication").value(containsString("local")))
        .andExpect(jsonPath("$.data.uptimeSeconds").isNumber());
  }
}
