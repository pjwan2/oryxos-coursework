package io.oryxos.web.api;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.AgentSessionStore;
import io.oryxos.core.agent.SessionIds;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Local-only real-model integration lab. Does not replace the scripted lab. The service is bound to
 * 127.0.0.1 and the test tool is read-only.
 */
@RestController
@RequestMapping("/api/v1/labs/spring-ai/payment")
public class SpringAiPaymentController {
  private final AgentService agent;
  private final AgentSessionStore sessions;

  public SpringAiPaymentController(
      @Qualifier("springAiAgentService") AgentService agent, AgentSessionStore sessions) {
    this.agent = agent;
    this.sessions = sessions;
  }

  public record PaymentQuestion(String userId, String content) {}

  @PostMapping("/chat")
  public ApiResponse<Map<String, String>> chat(@RequestBody PaymentQuestion req) {
    if (req == null
        || req.content() == null
        || req.content().isBlank()
        || req.content().length() > 32_768) {
      throw new IllegalArgumentException("Invalid message");
    }
    String id = SessionIds.of("real", req.userId(), "payment-real");
    String answer = agent.process("real", req.userId(), "payment-real", req.content());
    return ApiResponse.ok(Map.of("sessionId", id, "reply", answer));
  }

  @GetMapping("/sessions/{userId}")
  public ApiResponse<Map<String, Object>> session(@PathVariable String userId) {
    String id = SessionIds.of("real", userId, "payment-real");
    return sessions
        .find(id)
        .map(
            s ->
                ApiResponse.ok(
                    Map.<String, Object>of("sessionId", s.id(), "messages", s.transcript())))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
  }
}
