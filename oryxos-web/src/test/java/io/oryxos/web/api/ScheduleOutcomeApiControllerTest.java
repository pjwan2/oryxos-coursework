package io.oryxos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.agent.ToolCallHistory;
import io.oryxos.core.agent.ToolCallRecord;
import io.oryxos.core.schedule.ScheduleNotFoundException;
import io.oryxos.core.schedule.ScheduleService;
import io.oryxos.core.schedule.TaskExecution;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Technical status and tool audit kept apart: a refusal is never reported as a plain success. */
class ScheduleOutcomeApiControllerTest {
  private static final Instant T = Instant.parse("2026-03-01T10:00:00Z");
  private static final String SESSION = "scheduler:scheduler:ops";

  private final ScheduleService schedules = mock(ScheduleService.class);
  private final ToolCallHistory history = mock(ToolCallHistory.class);
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new ScheduleOutcomeApiController(schedules, history))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private static TaskExecution run(boolean success) {
    return new TaskExecution(
        7,
        "ops.report",
        SESSION,
        "manual",
        T,
        success,
        success ? null : "Agent run failed (X)",
        250);
  }

  private static ToolCallRecord call(boolean success, String category) {
    return new ToolCallRecord(
        1,
        SESSION,
        "file_write",
        success,
        category,
        success ? null : "reason",
        3,
        T.plusMillis(10));
  }

  private void givenRun(TaskExecution execution, ToolCallRecord... calls) {
    when(schedules.executions("ops.report", 200)).thenReturn(List.of(execution));
    when(history.find(eq(SESSION), any(Instant.class), any(Instant.class), anyInt()))
        .thenReturn(List.of(calls));
  }

  @Test
  void aCleanRunIsCompleted() throws Exception {
    givenRun(run(true), call(true, null));
    mvc.perform(get("/api/v1/schedules/ops.report/executions/7/outcome"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.technicalStatus").value("SUCCESS"))
        .andExpect(jsonPath("$.data.outcome").value("COMPLETED"))
        .andExpect(jsonPath("$.data.toolCalls").value(1))
        .andExpect(jsonPath("$.data.toolSucceeded").value(1));
  }

  @Test
  void aRunWhoseToolCallWasRefusedIsNotReportedAsASuccess() throws Exception {
    givenRun(run(true), call(false, "SandboxDenied"));
    mvc.perform(get("/api/v1/schedules/ops.report/executions/7/outcome"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.technicalStatus").value("SUCCESS"))
        .andExpect(jsonPath("$.data.outcome").value("COMPLETED_WITH_TOOL_ISSUES"))
        .andExpect(jsonPath("$.data.toolDenied").value(1))
        .andExpect(jsonPath("$.data.toolFailed").value(0))
        .andExpect(
            jsonPath("$.data.note")
                .value(org.hamcrest.Matchers.containsString("not a confirmed business success")));
  }

  @Test
  void aFailedToolCallCountsAsAnIssueToo() throws Exception {
    givenRun(run(true), call(true, null), call(false, "ToolReturnedFailure"));
    mvc.perform(get("/api/v1/schedules/ops.report/executions/7/outcome"))
        .andExpect(jsonPath("$.data.outcome").value("COMPLETED_WITH_TOOL_ISSUES"))
        .andExpect(jsonPath("$.data.toolFailed").value(1))
        .andExpect(jsonPath("$.data.toolSucceeded").value(1));
  }

  @Test
  void aRunThatThrewIsFailed() throws Exception {
    givenRun(run(false));
    mvc.perform(get("/api/v1/schedules/ops.report/executions/7/outcome"))
        .andExpect(jsonPath("$.data.technicalStatus").value("FAILED"))
        .andExpect(jsonPath("$.data.outcome").value("FAILED"));
  }

  @Test
  void aRunWithoutToolCallsSaysSo() throws Exception {
    givenRun(run(true));
    mvc.perform(get("/api/v1/schedules/ops.report/executions/7/outcome"))
        .andExpect(jsonPath("$.data.outcome").value("COMPLETED"))
        .andExpect(jsonPath("$.data.note").value("The run finished and made no tool calls."));
  }

  @Test
  void theToolAuditIsReadFromTheRunsSessionAndTimeWindow() throws Exception {
    givenRun(run(true));
    mvc.perform(get("/api/v1/schedules/ops.report/executions/7/outcome"))
        .andExpect(status().isOk());
    verify(history).find(SESSION, T, T.plusMillis(250 + 1000), 200);
  }

  @Test
  void unknownTasksAndExecutionsAre404() throws Exception {
    when(schedules.executions("nope", 200)).thenThrow(new ScheduleNotFoundException("nope"));
    mvc.perform(get("/api/v1/schedules/nope/executions/7/outcome"))
        .andExpect(status().isNotFound());
    when(schedules.executions("ops.report", 200)).thenReturn(List.of(run(true)));
    mvc.perform(get("/api/v1/schedules/ops.report/executions/999/outcome"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Execution not found"));
  }

  @Test
  void nonLocalCallersAreRefusedBecauseTheViewIsDerivedFromTheToolAudit() throws Exception {
    mvc.perform(
            get("/api/v1/schedules/ops.report/executions/7/outcome")
                .with(
                    r -> {
                      r.setRemoteAddr("203.0.113.9");
                      return r;
                    }))
        .andExpect(status().isForbidden());
    verify(history, never()).find(any(), any(), any(), anyInt());
  }
}
