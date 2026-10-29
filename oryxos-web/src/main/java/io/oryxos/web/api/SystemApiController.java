package io.oryxos.web.api;

import io.oryxos.core.agent.AgentSessionStore;
import io.oryxos.core.profile.ProfileCatalog;
import io.oryxos.core.schedule.ScheduleService;
import io.oryxos.core.tool.ToolRegistry;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness and a small status page. Nothing here reveals configuration values: no database URL, no
 * API keys, no webhook targets, only counts and the names of switches.
 */
@Tag(name = "System")
@RestController
@RequestMapping("/api/v1")
public class SystemApiController {
  private static final Logger LOG = LoggerFactory.getLogger(SystemApiController.class);
  private static final String STAGE = "b4";

  private final AgentSessionStore sessions;
  private final ProfileCatalog profiles;
  private final ToolRegistry tools;
  private final ScheduleService schedules;
  private final ObjectProvider<BuildProperties> build;
  private final String memoryBackend;
  private final boolean schedulerEnabled;
  private final String bindAddress;

  public SystemApiController(
      AgentSessionStore sessions,
      ProfileCatalog profiles,
      ToolRegistry tools,
      ScheduleService schedules,
      ObjectProvider<BuildProperties> build,
      @Value("${oryxos.memory.backend:markdown}") String memoryBackend,
      @Value("${oryxos.scheduler.enabled:true}") boolean schedulerEnabled,
      @Value("${server.address:}") String bindAddress) {
    this.sessions = sessions;
    this.profiles = profiles;
    this.tools = tools;
    this.schedules = schedules;
    this.build = build;
    this.memoryBackend = memoryBackend;
    this.schedulerEnabled = schedulerEnabled;
    this.bindAddress = bindAddress;
  }

  /** 200 with {@code status=ok}; 503 with {@code status=down} when the database is unreachable. */
  @GetMapping("/health")
  public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
    Map<String, Object> checks = new LinkedHashMap<>();
    boolean up = true;
    try {
      sessions.count();
      checks.put("database", "ok");
    } catch (RuntimeException e) {
      LOG.error("Health check: database is not reachable", e);
      checks.put("database", "down");
      up = false;
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("status", up ? "ok" : "down");
    data.put("checks", checks);
    if (up) {
      return ResponseEntity.ok(ApiResponse.ok(data));
    }
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(new ApiResponse<>(503, "Service unavailable", data, Instant.now()));
  }

  @GetMapping("/info")
  public ApiResponse<Map<String, Object>> info() {
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("application", "oryxos");
    info.put("stage", STAGE);
    BuildProperties buildInfo = build.getIfAvailable();
    info.put("version", buildInfo == null ? "dev" : buildInfo.getVersion());
    info.put("javaVersion", System.getProperty("java.version"));
    long startMillis = ManagementFactory.getRuntimeMXBean().getStartTime();
    info.put("startedAt", Instant.ofEpochMilli(startMillis));
    info.put("uptimeSeconds", (System.currentTimeMillis() - startMillis) / 1000);
    info.put("bindAddress", bindAddress.isBlank() ? "default" : bindAddress.strip());
    info.put("database", "sqlite");
    info.put("memoryBackend", memoryBackend.strip());
    info.put("schedulerEnabled", schedulerEnabled);
    info.put("profiles", profiles.list().size());
    info.put("tools", tools.names().size());
    info.put("schedules", schedules.list().size());
    info.put("sessions", sessions.count());
    info.put("authentication", "none: trusted local use only");
    return ApiResponse.ok(info);
  }
}
