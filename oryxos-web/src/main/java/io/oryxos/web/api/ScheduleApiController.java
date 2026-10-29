package io.oryxos.web.api;

import io.oryxos.core.schedule.ScheduleService;
import io.oryxos.core.schedule.ScheduleUpdate;
import io.oryxos.core.schedule.ScheduleView;
import io.oryxos.core.schedule.TaskExecution;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operations API for scheduled agent tasks. Reads are open to whoever can reach the (loopback
 * bound) server; the two writes additionally require local access, see {@link LocalAccess}.
 */
@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleApiController {
  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 200;

  private final ScheduleService schedules;

  public ScheduleApiController(ScheduleService schedules) {
    this.schedules = schedules;
  }

  @GetMapping
  public ApiResponse<List<ScheduleView>> list() {
    return ApiResponse.ok(schedules.list());
  }

  @GetMapping("/{id}/executions")
  public ApiResponse<List<TaskExecution>> executions(
      @PathVariable("id") String id,
      @RequestParam(name = "limit", required = false) Integer limit) {
    int effective = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
    return ApiResponse.ok(schedules.executions(id, effective));
  }

  /**
   * Runs the task now and returns its execution record (a failed run is a 200 with success=false).
   */
  @PostMapping("/{id}/run")
  public ApiResponse<TaskExecution> run(@PathVariable("id") String id, HttpServletRequest request) {
    LocalAccess.require(request);
    return ApiResponse.ok(schedules.runNow(id));
  }

  /** Body fields are optional: enabled, cron, zone, message. */
  @PutMapping("/{id}")
  public ApiResponse<ScheduleView> update(
      @PathVariable("id") String id,
      @RequestBody ScheduleUpdate update,
      HttpServletRequest request) {
    LocalAccess.require(request);
    return ApiResponse.ok(schedules.update(id, update));
  }
}
