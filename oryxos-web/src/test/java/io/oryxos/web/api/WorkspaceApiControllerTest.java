package io.oryxos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.profile.AgentDefinitionException;
import io.oryxos.core.workspace.WorkspaceEntry;
import io.oryxos.core.workspace.WorkspaceFile;
import io.oryxos.core.workspace.WorkspaceFiles;
import io.oryxos.core.workspace.WorkspacePathException;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** HTTP contract of the workspace API; the path rules themselves are tested on real files. */
class WorkspaceApiControllerTest {
  private final WorkspaceFiles workspace = mock(WorkspaceFiles.class);
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new WorkspaceApiController(workspace))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private static MockHttpServletRequestBuilder writeBody(String body) {
    return post("/api/v1/workspace/file").contentType(MediaType.APPLICATION_JSON).content(body);
  }

  @Test
  void theTreeIsTheListFromTheWorkspace() throws Exception {
    when(workspace.tree())
        .thenReturn(
            List.of(new WorkspaceEntry("a", true, 0), new WorkspaceEntry("a/AGENT.md", false, 40)));

    mvc.perform(get("/api/v1/workspace/tree"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[1].path").value("a/AGENT.md"))
        .andExpect(jsonPath("$.data[0].directory").value(true));
  }

  @Test
  void readPassesTheExactQueryPathAndReturnsTheFile() throws Exception {
    when(workspace.read("a/AGENT.md")).thenReturn(new WorkspaceFile("a/AGENT.md", "text", 4, true));

    mvc.perform(get("/api/v1/workspace/file").param("path", "a/AGENT.md"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").value("text"))
        .andExpect(jsonPath("$.data.editable").value(true));
    mvc.perform(get("/api/v1/workspace/file")).andExpect(status().isBadRequest());
  }

  @Test
  void writeSavesAndAnInvalidAgentMdIsTheValidationTextWithStatus400() throws Exception {
    when(workspace.save("a/AGENT.md", "new"))
        .thenReturn(new WorkspaceFile("a/AGENT.md", "new", 3, true));
    mvc.perform(writeBody("{\"path\":\"a/AGENT.md\",\"content\":\"new\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").value("new"));

    when(workspace.save(anyString(), anyString()))
        .thenThrow(
            new AgentDefinitionException(
                "agents/a/AGENT.md: description is required and must be text"));
    mvc.perform(writeBody("{\"path\":\"a/AGENT.md\",\"content\":\"bad\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.message")
                .value("agents/a/AGENT.md: description is required and must be text"));
  }

  @Test
  void aRefusedPathIs400WithoutEchoingTheRequestedPath() throws Exception {
    when(workspace.read(anyString()))
        .thenThrow(
            new WorkspacePathException(
                "Workspace path refused: the path must not contain empty, '.' or '..' segments"));

    mvc.perform(get("/api/v1/workspace/file").param("path", "../../etc/passwd"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.message")
                .value(
                    "Workspace path refused: the path must not contain empty, '.' or '..' segments"));
  }

  @Test
  void aBodyMissingAFieldIs400AndTheWorkspaceIsNotTouched() throws Exception {
    mvc.perform(writeBody("{\"path\":\"a/AGENT.md\"}")).andExpect(status().isBadRequest());
    mvc.perform(writeBody("{\"content\":\"x\"}")).andExpect(status().isBadRequest());
    mvc.perform(writeBody("not json")).andExpect(status().isBadRequest());
    verify(workspace, never()).save(anyString(), anyString());
  }

  @Test
  void everyEndpointRefusesANonLocalCallerACrossSiteBrowserAndARebindingHost() throws Exception {
    List<Supplier<MockHttpServletRequestBuilder>> endpoints =
        List.of(
            () -> get("/api/v1/workspace/tree"),
            () -> get("/api/v1/workspace/file").param("path", "a/AGENT.md"),
            () -> writeBody("{\"path\":\"a/AGENT.md\",\"content\":\"x\"}"));
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
            r -> r.header("Forwarded", "for=203.0.113.9"));
    int checked = 0;
    for (Supplier<MockHttpServletRequestBuilder> endpoint : endpoints) {
      for (UnaryOperator<MockHttpServletRequestBuilder> attack : attacks) {
        mvc.perform(attack.apply(endpoint.get())).andExpect(status().isForbidden());
        checked++;
      }
    }
    assertEquals(12, checked);
    verifyNoInteractions(workspace);
  }
}
