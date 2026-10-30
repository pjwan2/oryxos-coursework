package io.oryxos.web.api;

import io.oryxos.core.agent.AgentMessage;
import io.oryxos.core.agent.AgentSession;
import io.oryxos.core.agent.AgentSessionStore;
import io.oryxos.core.agent.SessionIds;
import io.oryxos.core.memory.AgentMemories;
import io.oryxos.core.memory.MemoryEntry;
import io.oryxos.core.profile.AgentDraft;
import io.oryxos.core.profile.AgentDrafting;
import io.oryxos.core.profile.AgentFile;
import io.oryxos.core.profile.AgentManagement;
import io.oryxos.core.profile.AgentView;
import io.oryxos.core.profile.ArchiveResult;
import io.oryxos.core.profile.ProfileCatalog;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Create, read, change and archive agents, plus each agent's fixed console conversation, its
 * private memory and the draft/save flow for its files. Everything is limited to local access: it
 * changes what runs on this machine. The agent itself is run by {@link AgentApiController} and
 * {@link AgentInvoker}; this class never executes a model or a tool.
 */
@Tag(name = "Agent management")
@RestController
@RequestMapping("/api/v1/agents")
public class AgentManagementApiController {
  /** Channel and user of the fixed console conversation: one stable session per agent. */
  static final String CONSOLE_CHANNEL = "admin";

  static final String CONSOLE_USER = "console";
  static final int MAX_HISTORY = 100;
  static final int DEFAULT_ARCHIVAL = 100;
  static final int MAX_ARCHIVAL = 500;

  private final AgentManagement agents;
  private final AgentDrafting drafting;
  private final AgentInvoker invoker;
  private final AgentSessionStore sessions;
  private final ProfileCatalog profiles;
  private final AgentMemories memories;
  private final String memoryBackend;

  public AgentManagementApiController(
      AgentManagement agents,
      AgentDrafting drafting,
      AgentInvoker invoker,
      AgentSessionStore sessions,
      ProfileCatalog profiles,
      AgentMemories memories,
      @Value("${oryxos.memory.backend:markdown}") String memoryBackend) {
    this.agents = agents;
    this.drafting = drafting;
    this.invoker = invoker;
    this.sessions = sessions;
    this.profiles = profiles;
    this.memories = memories;
    this.memoryBackend = memoryBackend;
  }

  public record CreateAgent(String name, String description) {}

  public record UpdateAgent(String agentMd) {}

  public record GenerateFiles(String description) {}

  public record SaveFiles(List<AgentFile> files) {
    public SaveFiles {
      files = files == null ? List.of() : List.copyOf(files);
    }

    @Override
    public List<AgentFile> files() {
      return List.copyOf(files);
    }
  }

  public record ConsoleMessage(String message) {}

  public record AgentListing(List<AgentView> agents, Map<String, String> problems) {
    public AgentListing {
      agents = List.copyOf(agents);
      problems = Map.copyOf(problems);
    }

    @Override
    public List<AgentView> agents() {
      return List.copyOf(agents);
    }

    @Override
    public Map<String, String> problems() {
      return Map.copyOf(problems);
    }
  }

  public record ConsoleSession(
      String sessionId,
      String agent,
      boolean exists,
      int totalMessages,
      boolean truncated,
      List<AgentMessage> messages) {
    public ConsoleSession {
      messages = List.copyOf(messages);
    }

    @Override
    public List<AgentMessage> messages() {
      return List.copyOf(messages);
    }
  }

  public record ConsoleReply(String sessionId, String reply) {}

  /** {@code shared} is true for a legacy agent: it reads and writes the global memory. */
  public record AgentMemoryView(
      String agent,
      boolean privateMemory,
      boolean shared,
      String backend,
      List<MemoryEntry> core,
      List<MemoryEntry> archival,
      int archivalLimit) {
    public AgentMemoryView {
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

  @PostMapping
  public ResponseEntity<ApiResponse<AgentView>> create(
      @RequestBody CreateAgent body, HttpServletRequest request) {
    LocalAccess.require(request);
    if (body == null) {
      throw new InvalidRequestException("name and description are required");
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(agents.create(body.name(), body.description())));
  }

  @GetMapping
  public ApiResponse<AgentListing> list(HttpServletRequest request) {
    LocalAccess.require(request);
    return ApiResponse.ok(new AgentListing(agents.list(), agents.problems()));
  }

  @GetMapping("/{name}")
  public ApiResponse<AgentView> get(@PathVariable("name") String name, HttpServletRequest request) {
    LocalAccess.require(request);
    return ApiResponse.ok(agents.get(name));
  }

  @PutMapping("/{name}")
  public ApiResponse<AgentView> update(
      @PathVariable("name") String name,
      @RequestBody UpdateAgent body,
      HttpServletRequest request) {
    LocalAccess.require(request);
    if (body == null || body.agentMd() == null) {
      throw new InvalidRequestException("agentMd is required");
    }
    return ApiResponse.ok(agents.update(name, body.agentMd()));
  }

  /** Archives; the directory and its memory are moved, not deleted, and history stays. */
  @DeleteMapping("/{name}")
  public ApiResponse<ArchiveResult> archive(
      @PathVariable("name") String name, HttpServletRequest request) {
    LocalAccess.require(request);
    return ApiResponse.ok(agents.archive(name));
  }

  @GetMapping("/{name}/session")
  public ApiResponse<ConsoleSession> session(
      @PathVariable("name") String name, HttpServletRequest request) {
    LocalAccess.require(request);
    profiles.get(name);
    String id = consoleSessionId(name);
    AgentSession session = sessions.find(id).orElse(null);
    if (session == null) {
      return ApiResponse.ok(new ConsoleSession(id, name, false, 0, false, List.of()));
    }
    List<AgentMessage> all = session.transcript();
    List<AgentMessage> tail = all.subList(Math.max(0, all.size() - MAX_HISTORY), all.size());
    return ApiResponse.ok(
        new ConsoleSession(id, name, true, all.size(), tail.size() < all.size(), tail));
  }

  @PostMapping("/{name}/session/messages")
  public ApiResponse<ConsoleReply> sendToSession(
      @PathVariable("name") String name,
      @RequestBody ConsoleMessage body,
      HttpServletRequest request) {
    LocalAccess.require(request);
    if (body == null) {
      throw new InvalidRequestException("message is required");
    }
    AgentInvoker.requireMessage(body.message());
    profiles.get(name);
    String reply = invoker.send(CONSOLE_CHANNEL, CONSOLE_USER, name, body.message());
    return ApiResponse.ok(new ConsoleReply(consoleSessionId(name), reply));
  }

  @PostMapping("/{name}/generate-files")
  public ApiResponse<AgentDraft> generate(
      @PathVariable("name") String name,
      @RequestBody GenerateFiles body,
      HttpServletRequest request) {
    LocalAccess.require(request);
    if (body == null) {
      throw new InvalidRequestException("description is required");
    }
    return ApiResponse.ok(drafting.generate(name, body.description()));
  }

  @PostMapping("/{name}/files")
  public ApiResponse<AgentView> saveFiles(
      @PathVariable("name") String name, @RequestBody SaveFiles body, HttpServletRequest request) {
    LocalAccess.require(request);
    if (body == null) {
      throw new InvalidRequestException("files are required");
    }
    return ApiResponse.ok(agents.saveFiles(name, body.files()));
  }

  @GetMapping("/{name}/memory")
  public ApiResponse<AgentMemoryView> memory(
      @PathVariable("name") String name,
      @RequestParam(name = "limit", required = false) Integer limit,
      HttpServletRequest request) {
    LocalAccess.require(request);
    profiles.get(name);
    int effective = limit == null ? DEFAULT_ARCHIVAL : Math.min(Math.max(limit, 1), MAX_ARCHIVAL);
    boolean isolated = memories.isolated(name);
    var store = memories.forAgent(name);
    return ApiResponse.ok(
        new AgentMemoryView(
            name,
            isolated,
            !isolated,
            memoryBackend.strip(),
            store.readCore(),
            store.readArchival(effective),
            effective));
  }

  private static String consoleSessionId(String name) {
    try {
      return SessionIds.of(CONSOLE_CHANNEL, CONSOLE_USER, name);
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException("Invalid agent name");
    }
  }
}
