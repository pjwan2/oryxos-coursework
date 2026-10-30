package io.oryxos.web.api;

import io.oryxos.core.agent.ToolCallHistory;
import io.oryxos.core.agent.ToolCallRecord;
import io.oryxos.core.schedule.ScheduleService;
import io.oryxos.core.schedule.TaskExecution;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a finished scheduled run actually achieved. {@code TaskExecution.success} only says that the
 * agent run did not throw; a run in which the sandbox refused the key tool call is "successful" in
 * that sense. This read-only view adds the tool audit of that run, so a report can say "completed
 * with tool issues" instead of "succeeded". The stored execution record and the existing endpoints
 * are unchanged.
 */
@Tag(name = "Scheduler")
@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleOutcomeApiController {
  /** Same window the admin console uses: from the start of the run to one second after its end. */
  private static final long SLACK_MILLIS = 1000;

  private static final int LOOKUP_LIMIT = 200;

  private final ScheduleService schedules;
  private final ToolCallHistory history;

  public ScheduleOutcomeApiController(ScheduleService schedules, ToolCallHistory history) {
    this.schedules = schedules;
    this.history = history;
  }

  /** Counts per tool-call result and the combined verdict. */
  public record ExecutionOutcome(
      long executionId,
      String technicalStatus,
      int toolCalls,
      int toolSucceeded,
      int toolDenied,
      int toolFailed,
      String outcome,
      String note) {

    public static final String COMPLETED = "COMPLETED";
    public static final String COMPLETED_WITH_TOOL_ISSUES = "COMPLETED_WITH_TOOL_ISSUES";
    public static final String FAILED = "FAILED";

    static ExecutionOutcome of(TaskExecution execution, List<ToolCallRecord> calls) {
      int succeeded = 0;
      int denied = 0;
      int failed = 0;
      for (ToolCallRecord call : calls) {
        if (call.success()) {
          succeeded++;
        } else if ("SandboxDenied".equals(call.errorCategory())) {
          denied++;
        } else {
          failed++;
        }
      }
      String technical = execution.success() ? "SUCCESS" : "FAILED";
      String outcome;
      String note;
      if (!execution.success()) {
        outcome = FAILED;
        note = "The agent run failed; see the execution's error message.";
      } else if (denied + failed > 0) {
        outcome = COMPLETED_WITH_TOOL_ISSUES;
        note =
            "The run finished, but "
                + (denied + failed)
                + " tool call(s) were refused or failed, so the requested action may not have"
                + " happened. This is not a confirmed business success.";
      } else {
        outcome = COMPLETED;
        note =
            calls.isEmpty()
                ? "The run finished and made no tool calls."
                : "The run finished and every tool call succeeded.";
      }
      return new ExecutionOutcome(
          execution.id(), technical, calls.size(), succeeded, denied, failed, outcome, note);
    }
  }

  /** 404 for an unknown task or execution (only the latest 200 executions are looked up). */
  @GetMapping("/{id}/executions/{executionId}/outcome")
  public ApiResponse<ExecutionOutcome> outcome(
      @PathVariable("id") String id,
      @PathVariable("executionId") long executionId,
      HttpServletRequest request) {
    LocalAccess.require(request);
    TaskExecution execution =
        schedules.executions(id, LOOKUP_LIMIT).stream()
            .filter(e -> e.id() == executionId)
            .findFirst()
            .orElseThrow(() -> new ApiNotFoundException("Execution"));
    Instant until = execution.startedAt().plusMillis(execution.durationMs() + SLACK_MILLIS);
    List<ToolCallRecord> calls =
        history.find(execution.sessionId(), execution.startedAt(), until, LOOKUP_LIMIT);
    return ApiResponse.ok(ExecutionOutcome.of(execution, calls));
  }
}
