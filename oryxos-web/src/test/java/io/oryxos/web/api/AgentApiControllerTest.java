package io.oryxos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.ModelTimeoutException;
import io.oryxos.core.agent.ProfileNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** POST /api/v1/agents/{name}/invoke goes through the shared AgentService, nothing else. */
class AgentApiControllerTest {
  private final AgentService agent = mock(AgentService.class);
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new AgentApiController(new AgentInvoker(agent)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private org.springframework.test.web.servlet.ResultActions invoke(String name, String body)
      throws Exception {
    return mvc.perform(
        post("/api/v1/agents/" + name + "/invoke")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  @Test
  void invokeRunsTheNamedAgentOnTheApiChannelWithADefaultUser() throws Exception {
    when(agent.process("api", "default", "helper", "ping")).thenReturn("pong");
    invoke("helper", "{\"message\":\"ping\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.agent").value("helper"))
        .andExpect(jsonPath("$.data.sessionId").value("api:default:helper"))
        .andExpect(jsonPath("$.data.reply").value("pong"));
  }

  @Test
  void aCallerSuppliedUserIdSelectsTheConversation() throws Exception {
    when(agent.process("api", "carol", "helper", "ping")).thenReturn("pong");
    invoke("helper", "{\"message\":\"ping\",\"userId\":\"carol\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value("api:carol:helper"));
  }

  @Test
  void anUnknownAgentIs404() throws Exception {
    when(agent.process(any(), any(), any(), any())).thenThrow(new ProfileNotFoundException("nope"));
    invoke("nope", "{\"message\":\"ping\"}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Unknown agent profile"));
  }

  @Test
  void badInputIs400AndNeverReachesTheAgent() throws Exception {
    invoke("helper", "{\"message\":\"\"}").andExpect(status().isBadRequest());
    invoke("helper", "{}").andExpect(status().isBadRequest());
    invoke("helper", "{\"message\":\"x\",\"userId\":\"a:b\"}").andExpect(status().isBadRequest());
    invoke("bad name", "{\"message\":\"x\"}").andExpect(status().isBadRequest());
    invoke("helper", "{\"message\":\"" + "x".repeat(32 * 1024 + 1) + "\"}")
        .andExpect(status().isBadRequest());
    invoke("helper", "{broken").andExpect(status().isBadRequest());
    verify(agent, never()).process(any(), any(), any(), any());
  }

  @Test
  void aModelTimeoutIs504() throws Exception {
    when(agent.process(any(), any(), any(), any())).thenThrow(new ModelTimeoutException("p"));
    invoke("helper", "{\"message\":\"ping\"}").andExpect(status().isGatewayTimeout());
  }

  @Test
  void nonLocalCallersAreRefused() throws Exception {
    mvc.perform(
            post("/api/v1/agents/helper/invoke")
                .with(
                    request -> {
                      request.setRemoteAddr("198.51.100.4");
                      return request;
                    })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"ping\"}"))
        .andExpect(status().isForbidden());
    verify(agent, never()).process(any(), any(), any(), any());
  }
}
