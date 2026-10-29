package io.oryxos.web.api;

import io.oryxos.core.memory.LongTermMemoryStore;
import io.oryxos.core.memory.MemoryEntry;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of long-term memory. Memory is personal data, so reads are local-only. */
@Tag(name = "Memory")
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryApiController {
  static final int DEFAULT_ARCHIVAL = 100;
  static final int MAX_ARCHIVAL = 500;

  private final LongTermMemoryStore store;
  private final String backend;

  public MemoryApiController(
      LongTermMemoryStore store, @Value("${oryxos.memory.backend:markdown}") String backend) {
    this.store = store;
    this.backend = backend;
  }

  /** CORE is returned in full; ARCHIVAL is the most recent {@code archivalLimit} entries. */
  public record MemoryOverview(
      String backend, List<MemoryEntry> core, List<MemoryEntry> archival, int archivalLimit) {
    public MemoryOverview {
      core = List.copyOf(core);
      archival = List.copyOf(archival);
    }

    @Override
    public List<MemoryEntry> core() {
      return List.copyOf(core);
    }

    @Override
    public List<MemoryEntry> archival() {
      return List.copyOf(archival);
    }
  }

  @GetMapping
  public ApiResponse<MemoryOverview> overview(
      @RequestParam(name = "limit", required = false) Integer limit, HttpServletRequest request) {
    LocalAccess.require(request);
    int effective = limit == null ? DEFAULT_ARCHIVAL : Math.min(Math.max(limit, 1), MAX_ARCHIVAL);
    return ApiResponse.ok(
        new MemoryOverview(
            backend.strip(), store.readCore(), store.readArchival(effective), effective));
  }
}
