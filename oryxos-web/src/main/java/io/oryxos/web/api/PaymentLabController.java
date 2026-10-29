package io.oryxos.web.api;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.AgentSessionStore;
import io.oryxos.core.agent.SessionIds;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Lesson-only read-only payment demo. Bound to 127.0.0.1 by application.yaml. */
@RestController
@RequestMapping("/api/v1/labs/payment")
public class PaymentLabController {
  private final AgentService agent;
  private final AgentSessionStore sessions;

  public PaymentLabController(AgentService agent, AgentSessionStore sessions) {
    this.agent = agent;
    this.sessions = sessions;
  }

  public record PaymentQuestion(String userId, String content) {}

  @PostMapping("/chat")
  public ApiResponse<Map<String, String>> chat(@RequestBody PaymentQuestion req) {
    if (req == null || req.content() == null || req.content().length() > 32_768) {
      throw new IllegalArgumentException("Invalid message");
    }
    String id = SessionIds.of("web", req.userId(), "payment-demo");
    String answer = agent.process("web", req.userId(), "payment-demo", req.content());
    return ApiResponse.ok(Map.of("sessionId", id, "reply", answer));
  }

  @GetMapping("/sessions/{userId}")
  public ApiResponse<Map<String, Object>> session(@PathVariable String userId) {
    String id = SessionIds.of("web", userId, "payment-demo");
    return sessions
        .find(id)
        .map(
            s ->
                ApiResponse.ok(
                    Map.<String, Object>of("sessionId", s.id(), "messages", s.transcript())))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
  }
}
