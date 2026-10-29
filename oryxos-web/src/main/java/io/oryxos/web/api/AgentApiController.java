package io.oryxos.web.api;

import io.oryxos.core.agent.SessionIds;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** One-shot agent call for scripts: channel "api", one conversation per (agent, userId). */
@Tag(name = "Agent")
@RestController
@RequestMapping("/api/v1/agents")
public class AgentApiController {
  static final String CHANNEL = "api";
  static final String DEFAULT_USER = "default";

  private final AgentInvoker invoker;

  public AgentApiController(AgentInvoker invoker) {
    this.invoker = invoker;
  }

  public record InvokeRequest(String message, String userId) {}

  public record InvokeReply(String agent, String sessionId, String reply) {}

  @PostMapping("/{name}/invoke")
  public ApiResponse<InvokeReply> invoke(
      @PathVariable("name") String name,
      @RequestBody InvokeRequest body,
      HttpServletRequest request) {
    LocalAccess.require(request);
    if (body == null) {
      throw new InvalidRequestException("message is required");
    }
    AgentInvoker.requireMessage(body.message());
    String userId = body.userId() == null ? DEFAULT_USER : body.userId();
    String reply = invoker.send(CHANNEL, userId, name, body.message());
    return ApiResponse.ok(new InvokeReply(name, SessionIds.of(CHANNEL, userId, name), reply));
  }
}
