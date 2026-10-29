package io.oryxos.web.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.agent.AgentMessage;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.AgentSession;
import io.oryxos.core.agent.AgentSessionStore;
import io.oryxos.core.agent.ModelTimeoutException;
import io.oryxos.core.agent.ModelUnavailableException;
import io.oryxos.core.agent.ProfileNotFoundException;
import io.oryxos.core.agent.SessionSummary;
import io.oryxos.core.profile.ProfileCatalog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** HTTP contract of the session API: shapes, limits, status codes and the local-only rule. */
class SessionApiControllerTest {
  private static final String ID = "web:alice:helper";
  private static final Instant T = Instant.parse("2026-03-01T00:00:00Z");

  private final AgentSessionStore store = mock(AgentSessionStore.class);
  private final ProfileCatalog profiles = mock(ProfileCatalog.class);
  private final AgentService agent = mock(AgentService.class);
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(
                new SessionApiController(store, profiles, new AgentInvoker(agent)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private static SessionSummary summary(String id) {
    return new SessionSummary(id, "helper", "web", "alice", 4, "hello", T, T.plusSeconds(60));
  }

  private static AgentSession sessionWith(int messages) {
    AgentSession session = new AgentSession(ID, "helper");
    for (int i = 0; i < messages; i++) {
      session.append(
          new AgentMessage(
              i % 2 == 0 ? AgentMessage.Role.USER : AgentMessage.Role.ASSISTANT, "m" + i));
    }
    return session;
  }

  private static String json(String content) {
    return "{\"content\":\"" + content + "\"}";
  }

  // ---- list -----------------------------------------------------------------------------

  @Test
  void listReturnsSummariesInTheStoreOrderWithDefaultLimit100() throws Exception {
    when(store.list(100))
        .thenReturn(List.of(summary("web:alice:helper"), summary("web:bob:helper")));
    mvc.perform(get("/api/v1/sessions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data[0].sessionId").value("web:alice:helper"))
        .andExpect(jsonPath("$.data[0].messageCount").value(4))
        .andExpect(jsonPath("$.data[0].lastUserMessage").value("hello"))
        .andExpect(jsonPath("$.data[1].sessionId").value("web:bob:helper"));
    verify(store).list(100);
  }

  @Test
  void listLimitIsClampedToTheRangeOneToHundred() throws Exception {
    when(store.list(any(Integer.class))).thenReturn(List.of());
    mvc.perform(get("/api/v1/sessions?limit=5000")).andExpect(status().isOk());
    verify(store).list(100);
    mvc.perform(get("/api/v1/sessions?limit=0")).andExpect(status().isOk());
    verify(store).list(1);
    mvc.perform(get("/api/v1/sessions?limit=7")).andExpect(status().isOk());
    verify(store).list(7);
    mvc.perform(get("/api/v1/sessions?limit=abc")).andExpect(status().isBadRequest());
  }

  // ---- create ---------------------------------------------------------------------------

  @Test
  void createMakesAndPersistsAnEmptySessionOn201() throws Exception {
    when(store.find("web:alice:helper")).thenReturn(Optional.empty());
    mvc.perform(
            post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profile\":\"helper\",\"userId\":\"alice\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.sessionId").value("web:alice:helper"))
        .andExpect(jsonPath("$.data.channel").value("web"))
        .andExpect(jsonPath("$.data.created").value(true));
    verify(profiles).get("helper");
    verify(store).save(any(AgentSession.class));
  }

  @Test
  void createWithoutUserIdGeneratesAFreshSafeOne() throws Exception {
    when(store.find(anyString())).thenReturn(Optional.empty());
    mvc.perform(
            post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profile\":\"helper\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.userId").value(matchesPattern("u[0-9a-f]{16}")));
  }

  @Test
  void createForAnExistingSessionIsIdempotent() throws Exception {
    when(store.find("web:alice:helper")).thenReturn(Optional.of(sessionWith(2)));
    mvc.perform(
            post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profile\":\"helper\",\"userId\":\"alice\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.created").value(false));
    verify(store, never()).save(any());
  }

  @Test
  void createForAnUnknownProfileIs404AndSavesNothing() throws Exception {
    when(profiles.get("ghost")).thenThrow(new ProfileNotFoundException("ghost"));
    mvc.perform(
            post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profile\":\"ghost\",\"userId\":\"alice\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Unknown agent profile"));
    verify(store, never()).save(any());
  }

  @Test
  void createRejectsMissingOrUnsafeIdentityParts() throws Exception {
    for (String body :
        List.of(
            "{}",
            "{\"profile\":\"helper\",\"userId\":\"a:b\"}",
            "{\"profile\":\"he lper\"}",
            "{\"profile\":\"helper\",\"userId\":\"\"}")) {
      mvc.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest());
    }
    verify(store, never()).save(any());
  }

  // ---- detail ---------------------------------------------------------------------------

  @Test
  void detailReturnsTheWholeTranscriptWhenItIsShort() throws Exception {
    when(store.find(ID)).thenReturn(Optional.of(sessionWith(3)));
    mvc.perform(get("/api/v1/sessions/" + ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value(ID))
        .andExpect(jsonPath("$.data.profile").value("helper"))
        .andExpect(jsonPath("$.data.userId").value("alice"))
        .andExpect(jsonPath("$.data.totalMessages").value(3))
        .andExpect(jsonPath("$.data.truncated").value(false))
        .andExpect(jsonPath("$.data.messages.length()").value(3))
        .andExpect(jsonPath("$.data.messages[0].role").value("USER"));
  }

  @Test
  void detailReturnsOnlyTheMostRecentHundredMessages() throws Exception {
    when(store.find(ID)).thenReturn(Optional.of(sessionWith(150)));
    mvc.perform(get("/api/v1/sessions/" + ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalMessages").value(150))
        .andExpect(jsonPath("$.data.truncated").value(true))
        .andExpect(jsonPath("$.data.messages.length()").value(100))
        .andExpect(jsonPath("$.data.messages[0].content").value("m50"))
        .andExpect(jsonPath("$.data.messages[99].content").value("m149"));
    mvc.perform(get("/api/v1/sessions/" + ID + "?limit=2"))
        .andExpect(jsonPath("$.data.messages.length()").value(2))
        .andExpect(jsonPath("$.data.messages[0].content").value("m148"));
    mvc.perform(get("/api/v1/sessions/" + ID + "?limit=100000"))
        .andExpect(jsonPath("$.data.messages.length()").value(100));
  }

  @Test
  void detailOfAnUnknownSessionIs404AndOfAMalformedIdIs400() throws Exception {
    when(store.find(anyString())).thenReturn(Optional.empty());
    mvc.perform(get("/api/v1/sessions/web:nobody:helper"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Session not found"));
    mvc.perform(get("/api/v1/sessions/not-a-session-id")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/sessions/a:b:c:d")).andExpect(status().isBadRequest());
  }

  // ---- messages -------------------------------------------------------------------------

  @Test
  void postingAMessageRunsTheOneAgentServiceWithTheIdentityFromTheSessionId() throws Exception {
    when(store.find(ID)).thenReturn(Optional.of(sessionWith(0)));
    when(agent.process("web", "alice", "helper", "hi there")).thenReturn("hello back");
    mvc.perform(
            post("/api/v1/sessions/" + ID + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("hi there")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value(ID))
        .andExpect(jsonPath("$.data.reply").value("hello back"));
  }

  @Test
  void postingToAnUnknownSessionIs404AndNeverCallsTheAgent() throws Exception {
    when(store.find(anyString())).thenReturn(Optional.empty());
    mvc.perform(
            post("/api/v1/sessions/" + ID + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("hi")))
        .andExpect(status().isNotFound());
    verify(agent, never()).process(any(), any(), any(), any());
  }

  @Test
  void blankMissingAndOversizedMessagesAreRejectedBeforeTheAgentRuns() throws Exception {
    when(store.find(ID)).thenReturn(Optional.of(sessionWith(0)));
    for (String body : List.of("{}", json("   "), "{\"content\":null}")) {
      mvc.perform(
              post("/api/v1/sessions/" + ID + "/messages")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("message is required"));
    }
    mvc.perform(
            post("/api/v1/sessions/" + ID + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("x".repeat(32 * 1024 + 1))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("32768")));
    verify(agent, never()).process(any(), any(), any(), any());
  }

  @Test
  void theLimitIs32KiBOfUtf8BytesNotCharacters() throws Exception {
    when(store.find(ID)).thenReturn(Optional.of(sessionWith(0)));
    when(agent.process(anyString(), anyString(), anyString(), anyString())).thenReturn("ok");
    // 10,923 CJK characters are 32,769 bytes: few characters, but over the byte limit.
    mvc.perform(
            post("/api/v1/sessions/" + ID + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("汉".repeat(10_923))))
        .andExpect(status().isBadRequest());
    // Exactly 32,768 bytes is accepted.
    mvc.perform(
            post("/api/v1/sessions/" + ID + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("x".repeat(32 * 1024))))
        .andExpect(status().isOk());
  }

  // ---- delete ---------------------------------------------------------------------------

  @Test
  void deleteRemovesAnExistingSessionAndReports404ForAMissingOne() throws Exception {
    when(store.discard(ID)).thenReturn(true);
    when(store.discard("web:ghost:helper")).thenReturn(false);
    mvc.perform(delete("/api/v1/sessions/" + ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    mvc.perform(delete("/api/v1/sessions/web:ghost:helper")).andExpect(status().isNotFound());
    mvc.perform(delete("/api/v1/sessions/bad")).andExpect(status().isBadRequest());
  }

  // ---- error semantics ------------------------------------------------------------------

  @Test
  void anUnknownProfileDuringARunIs404() throws Exception {
    runFails(new ProfileNotFoundException("gone"));
    postHi(ID).andExpect(status().isNotFound());
  }

  @Test
  void anUnreachableModelIs503() throws Exception {
    runFails(new ModelUnavailableException("deepseek"));
    postHi(ID)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(503))
        .andExpect(jsonPath("$.message").value("Model provider unavailable"));
  }

  @Test
  void aSlowModelIs504() throws Exception {
    runFails(new ModelTimeoutException("deepseek"));
    postHi(ID)
        .andExpect(status().isGatewayTimeout())
        .andExpect(jsonPath("$.code").value(504))
        .andExpect(jsonPath("$.message").value("Model provider timed out"));
  }

  @Test
  void anInternalFailureIs500AndLeaksNothing() throws Exception {
    runFails(new IllegalStateException("jdbc:sqlite:C:/secret/oryxos.db password=hunter2"));
    postHi(ID)
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value(500))
        .andExpect(jsonPath("$.message").value("Internal server error"))
        .andExpect(content().string(not(containsString("hunter2"))))
        .andExpect(content().string(not(containsString("jdbc"))))
        .andExpect(content().string(not(containsString("IllegalState"))));
  }

  @Test
  void anIllegalArgumentFromInsideTheRunIsAServerFaultNotA400() throws Exception {
    runFails(new IllegalArgumentException("max_iterations must be an integer"));
    postHi(ID)
        .andExpect(status().isInternalServerError())
        .andExpect(content().string(not(containsString("max_iterations"))));
  }

  // ---- local access ---------------------------------------------------------------------

  @Test
  void everyEndpointRefusesNonLocalCallersBeforeTouchingAnything() throws Exception {
    RequestPostProcessor remote =
        request -> {
          request.setRemoteAddr("203.0.113.9");
          return request;
        };
    mvc.perform(get("/api/v1/sessions").with(remote)).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/sessions/" + ID).with(remote)).andExpect(status().isForbidden());
    mvc.perform(delete("/api/v1/sessions/" + ID).with(remote)).andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/sessions")
                .with(remote)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profile\":\"helper\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/sessions/" + ID + "/messages")
                .with(remote)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("hi")))
        .andExpect(status().isForbidden());
    verify(store, never()).list(any(Integer.class));
    verify(store, never()).discard(any());
    verify(store, never()).save(any());
    verify(agent, never()).process(any(), any(), any(), any());
  }

  @Test
  void aReboundDnsNameInTheHostHeaderIsForbidden() throws Exception {
    mvc.perform(get("/api/v1/sessions").header("Host", "evil.example:8080"))
        .andExpect(status().isForbidden());
    when(store.list(100)).thenReturn(new ArrayList<>());
    mvc.perform(get("/api/v1/sessions").header("Host", "127.0.0.1:8080"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/sessions").header("Host", "localhost:8080"))
        .andExpect(status().isOk());
  }

  // ---- helpers --------------------------------------------------------------------------

  private void runFails(RuntimeException failure) {
    when(store.find(ID)).thenReturn(Optional.of(sessionWith(0)));
    when(agent.process(anyString(), anyString(), anyString(), anyString())).thenThrow(failure);
  }

  private ResultActions postHi(String id) throws Exception {
    return mvc.perform(
        post("/api/v1/sessions/" + id + "/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json("hi")));
  }
}
