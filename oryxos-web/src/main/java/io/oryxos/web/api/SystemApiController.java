package io.oryxos.web.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Probe endpoints. Agent APIs will be added after the runtime exists. */
@RestController
@RequestMapping("/api/v1")
public class SystemApiController {
  @GetMapping("/health")
  public ApiResponse<Map<String, String>> health() {
    return ApiResponse.ok(Map.of("status", "ok"));
  }

  @GetMapping("/info")
  public ApiResponse<Map<String, String>> info() {
    return ApiResponse.ok(Map.of("application", "oryxos", "stage", "gate-1"));
  }
}
