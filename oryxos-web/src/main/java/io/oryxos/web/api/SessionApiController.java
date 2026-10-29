package io.oryxos.web.api;

import io.oryxos.core.agent.AgentMessage;
import io.oryxos.core.agent.AgentSession;
import io.oryxos.core.agent.AgentSessionStore;
import io.oryxos.core.agent.SessionIds;
import io.oryxos.core.agent.SessionSummary;
import io.oryxos.core.profile.ProfileCatalog;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Conversations over HTTP. Every endpoint is limited to local access because transcripts can hold
 * anything a user typed. The agent itself runs in {@link AgentInvoker}.
 */
@Tag(name = "Session")
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionApiController {
  /** Sessions created through this API belong to the "web" channel. */
  static final String CHANNEL = "web";

  static final int MAX_LIST = 100;
  static final int MAX_HISTORY = 100;

  private final AgentSessionStore sessions;
  private final ProfileCatalog profiles;
  private final AgentInvoker invoker;

  public SessionApiController(
      AgentSessionStore sessions, ProfileCatalog profiles, AgentInvoker invoker) {
    this.sessions = sessions;
    this.profiles = profiles;
    this.invoker = invoker;
  }

  /** {@code userId} is optional; without it a fresh random one makes a brand-new conversation. */
  public record CreateSession(String profile, String userId) {}

  public record PostMessage(String content) {}

  public record SessionCreated(
      String sessionId, String profile, String channel, String userId, boolean created) {}

  public record MessageReply(String sessionId, String reply) {}

  /** The newest {@code messages} of a session; {@code truncated} says older ones were left out. */
  public record SessionDetail(
      String sessionId,
      String profile,
      String channel,
      String userId,
      int totalMessages,
      boolean truncated,
      List<AgentMessage> messages) {
    public SessionDetail {
      messages = List.copyOf(messages);
    }

    @Override
    public List<AgentMessage> messages() {
      return List.copyOf(messages);
    }
  }

  @PostMapping
  public ResponseEntity<ApiResponse<SessionCreated>> create(
      @RequestBody CreateSession body, HttpServletRequest request) {
    LocalAccess.require(request);
    if (body == null || body.profile() == null) {
      throw new InvalidRequestException("profile is required");
    }
    String userId = body.userId() == null ? newUserId() : body.userId();
    String id;
    try {
      id = SessionIds.of(CHANNEL, userId, body.profile());
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException("Invalid session identity");
    }
    profiles.get(body.profile());
    boolean exists = sessions.find(id).isPresent();
    if (!exists) {
      sessions.save(new AgentSession(id, body.profile()));
    }
    return ResponseEntity.status(exists ? HttpStatus.OK : HttpStatus.CREATED)
        .body(ApiResponse.ok(new SessionCreated(id, body.profile(), CHANNEL, userId, !exists)));
  }

  @PostMapping("/{id}/messages")
  public ApiResponse<MessageReply> post(
      @PathVariable("id") String id, @RequestBody PostMessage body, HttpServletRequest request) {
    LocalAccess.require(request);
    SessionRef ref = SessionRef.parse(id);
    AgentInvoker.requireMessage(body == null ? null : body.content());
    if (sessions.find(ref.id()).isEmpty()) {
      throw new ApiNotFoundException("Session");
    }
    String reply = invoker.send(ref.channel(), ref.userId(), ref.profile(), body.content());
    return ApiResponse.ok(new MessageReply(ref.id(), reply));
  }

  @GetMapping("/{id}")
  public ApiResponse<SessionDetail> get(
      @PathVariable("id") String id,
      @RequestParam(name = "limit", required = false) Integer limit,
      HttpServletRequest request) {
    LocalAccess.require(request);
    SessionRef ref = SessionRef.parse(id);
    AgentSession session =
        sessions.find(ref.id()).orElseThrow(() -> new ApiNotFoundException("Session"));
    List<AgentMessage> all = session.transcript();
    int keep = clamp(limit, MAX_HISTORY);
    List<AgentMessage> tail = all.subList(Math.max(0, all.size() - keep), all.size());
    return ApiResponse.ok(
        new SessionDetail(
            ref.id(),
            ref.profile(),
            ref.channel(),
            ref.userId(),
            all.size(),
            tail.size() < all.size(),
            List.copyOf(tail)));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable("id") String id, HttpServletRequest request) {
    LocalAccess.require(request);
    if (!sessions.discard(SessionRef.parse(id).id())) {
      throw new ApiNotFoundException("Session");
    }
    return new ApiResponse<>(0, "ok", null, Instant.now());
  }

  /** Most recently active first; default and maximum are both 100. */
  @GetMapping
  public ApiResponse<List<SessionSummary>> list(
      @RequestParam(name = "limit", required = false) Integer limit, HttpServletRequest request) {
    LocalAccess.require(request);
    return ApiResponse.ok(sessions.list(clamp(limit, MAX_LIST)));
  }

  private static int clamp(Integer requested, int max) {
    return requested == null ? max : Math.min(Math.max(requested, 1), max);
  }

  private static String newUserId() {
    return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }
}
