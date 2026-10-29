package io.oryxos.web.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.schedule.ScheduleConfigException;
import io.oryxos.core.schedule.ScheduleNotFoundException;
import io.oryxos.core.schedule.ScheduleService;
import io.oryxos.core.schedule.ScheduleUpdate;
import io.oryxos.core.schedule.ScheduleView;
import io.oryxos.core.schedule.SchedulerDisabledException;
import io.oryxos.core.schedule.TaskAlreadyRunningException;
import io.oryxos.core.schedule.TaskExecution;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** HTTP contract of the schedule operations API, including the local-only rule for writes. */
class ScheduleApiControllerTest {
  private static final Instant T = Instant.parse("2026-03-01T00:00:00Z");

  private final ScheduleService service = mock(ScheduleService.class);
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new ScheduleApiController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private static ScheduleView view(boolean enabled) {
    return new ScheduleView(
        "weather.morning",
        "weather",
        "0 0 9 * * *",
        "Asia/Shanghai",
        "Weather?",
        enabled,
        false,
        T,
        null,
        null,
        0);
  }

  private static TaskExecution execution(boolean success) {
    return new TaskExecution(
        7,
        "weather.morning",
        "scheduler:scheduler:weather",
        "manual",
        T,
        success,
        success ? null : "Agent run failed (IllegalStateException)",
        12);
  }

  private static RequestPostProcessor from(String address) {
    return request -> {
      request.setRemoteAddr(address);
      return request;
    };
  }

  // ---- reads ----------------------------------------------------------------------------

  @Test
  void listWrapsSchedulesInTheCommonEnvelope() throws Exception {
    when(service.list()).thenReturn(List.of(view(true)));
    mvc.perform(get("/api/v1/schedules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.message").value("ok"))
        .andExpect(jsonPath("$.data[0].taskId").value("weather.morning"))
        .andExpect(jsonPath("$.data[0].zone").value("Asia/Shanghai"))
        .andExpect(jsonPath("$.data[0].enabled").value(true))
        .andExpect(jsonPath("$.data[0].running").value(false))
        .andExpect(jsonPath("$.data[0].runCount").value(0));
  }

  @Test
  void executionsDefaultToFiftyAndAreClampedToTheLimits() throws Exception {
    when(service.executions(any(), any(Integer.class))).thenReturn(List.of(execution(true)));
    mvc.perform(get("/api/v1/schedules/weather.morning/executions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value(7))
        .andExpect(jsonPath("$.data[0].success").value(true))
        .andExpect(jsonPath("$.data[0].trigger").value("manual"));
    verify(service).executions("weather.morning", 50);

    mvc.perform(get("/api/v1/schedules/weather.morning/executions?limit=100000"))
        .andExpect(status().isOk());
    verify(service).executions("weather.morning", 200);
    mvc.perform(get("/api/v1/schedules/weather.morning/executions?limit=-4"))
        .andExpect(status().isOk());
    verify(service).executions("weather.morning", 1);
  }

  @Test
  void aNonNumericLimitIsAClientError() throws Exception {
    mvc.perform(get("/api/v1/schedules/weather.morning/executions?limit=abc"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void anUnknownScheduleIs404WithoutEchoingTheId() throws Exception {
    when(service.executions(eq("nope"), any(Integer.class)))
        .thenThrow(new ScheduleNotFoundException("nope"));
    mvc.perform(get("/api/v1/schedules/nope/executions"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("Unknown schedule"));
  }

  // ---- run ------------------------------------------------------------------------------

  @Test
  void runReturnsTheExecutionRecord() throws Exception {
    when(service.runNow("weather.morning")).thenReturn(execution(true));
    mvc.perform(post("/api/v1/schedules/weather.morning/run"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.success").value(true))
        .andExpect(jsonPath("$.data.sessionId").value("scheduler:scheduler:weather"));
  }

  @Test
  void aFailedRunIsStill200WithSuccessFalseAndASafeReason() throws Exception {
    when(service.runNow("weather.morning")).thenReturn(execution(false));
    mvc.perform(post("/api/v1/schedules/weather.morning/run"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.success").value(false))
        .andExpect(
            jsonPath("$.data.errorMessage").value("Agent run failed (IllegalStateException)"));
  }

  @Test
  void runningATaskThatIsAlreadyRunningIsAConflict() throws Exception {
    when(service.runNow("weather.morning"))
        .thenThrow(new TaskAlreadyRunningException("weather.morning"));
    mvc.perform(post("/api/v1/schedules/weather.morning/run"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409));
  }

  @Test
  void runningWhileTheSchedulerIsDisabledIsAConflict() throws Exception {
    when(service.runNow("weather.morning")).thenThrow(new SchedulerDisabledException());
    mvc.perform(post("/api/v1/schedules/weather.morning/run"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(containsString("disabled")));
  }

  @Test
  void runningAnUnknownTaskIs404() throws Exception {
    when(service.runNow("nope")).thenThrow(new ScheduleNotFoundException("nope"));
    mvc.perform(post("/api/v1/schedules/nope/run")).andExpect(status().isNotFound());
  }

  // ---- update ---------------------------------------------------------------------------

  @Test
  void updatePassesTheBodyToTheServiceAndReturnsTheNewView() throws Exception {
    when(service.update(eq("weather.morning"), any(ScheduleUpdate.class))).thenReturn(view(false));
    mvc.perform(
            put("/api/v1/schedules/weather.morning")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"cron\":\"0 0 8 * * *\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(false));
    verify(service).update("weather.morning", new ScheduleUpdate(false, "0 0 8 * * *", null, null));
  }

  @Test
  void anInvalidUpdateIs400WithTheValidationReason() throws Exception {
    when(service.update(eq("weather.morning"), any(ScheduleUpdate.class)))
        .thenThrow(new ScheduleConfigException("cron is not a valid six-field cron expression"));
    mvc.perform(
            put("/api/v1/schedules/weather.morning")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cron\":\"bogus\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("six-field")));
  }

  @Test
  void updateRejectsMalformedAndNonJsonBodies() throws Exception {
    mvc.perform(
            put("/api/v1/schedules/weather.morning")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            put("/api/v1/schedules/weather.morning")
                .contentType(MediaType.TEXT_PLAIN)
                .content("enabled=false"))
        .andExpect(status().isUnsupportedMediaType());
    verify(service, never()).update(any(), any());
  }

  // ---- local-only writes ----------------------------------------------------------------

  @Test
  void writesFromANonLocalAddressAreForbiddenAndNeverReachTheService() throws Exception {
    mvc.perform(post("/api/v1/schedules/weather.morning/run").with(from("203.0.113.9")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
    mvc.perform(
            put("/api/v1/schedules/weather.morning")
                .with(from("10.0.0.5"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isForbidden());
    verify(service, never()).runNow(any());
    verify(service, never()).update(any(), any());
  }

  @Test
  void loopbackAddressesInBothFamiliesAreAccepted() throws Exception {
    when(service.runNow("weather.morning")).thenReturn(execution(true));
    for (String address : List.of("127.0.0.1", "127.0.1.1", "::1", "0:0:0:0:0:0:0:1")) {
      mvc.perform(post("/api/v1/schedules/weather.morning/run").with(from(address)))
          .andExpect(status().isOk());
    }
  }

  @Test
  void aForwardedRequestIsTreatedAsRemoteEvenFromLoopback() throws Exception {
    mvc.perform(
            post("/api/v1/schedules/weather.morning/run").header("X-Forwarded-For", "203.0.113.9"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/schedules/weather.morning/run").header("Forwarded", "for=203.0.113.9"))
        .andExpect(status().isForbidden());
    verify(service, never()).runNow(any());
  }

  @Test
  void aCrossSiteBrowserRequestIsForbiddenButALocalPageIsFine() throws Exception {
    when(service.runNow("weather.morning")).thenReturn(execution(true));
    mvc.perform(
            post("/api/v1/schedules/weather.morning/run").header("Origin", "https://evil.example"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/schedules/weather.morning/run").header("Origin", "null"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/schedules/weather.morning/run").header("Origin", "http://localhost:5173"))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/schedules/weather.morning/run").header("Origin", "http://127.0.0.1:8080"))
        .andExpect(status().isOk());
  }

  @Test
  void readsAreNotRestrictedByTheLocalOnlyRule() throws Exception {
    when(service.list()).thenReturn(List.of());
    mvc.perform(get("/api/v1/schedules").with(from("203.0.113.9"))).andExpect(status().isOk());
  }
}
