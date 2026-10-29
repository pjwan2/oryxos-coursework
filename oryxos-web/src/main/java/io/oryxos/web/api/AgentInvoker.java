package io.oryxos.web.api;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.ModelTimeoutException;
import io.oryxos.core.agent.ModelUnavailableException;
import io.oryxos.core.agent.ProfileNotFoundException;
import io.oryxos.core.agent.SessionIds;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * The only way REST code runs an agent: validate, then hand over to the one {@link AgentService}
 * that the CLI and the scheduler use too. Inputs are fully checked before the call, so an {@link
 * IllegalArgumentException} coming out of the run is a server-side configuration fault (for example
 * a bad {@code max_iterations}) and must not be reported as the caller's mistake.
 */
@Component
public class AgentInvoker {
  /** Largest accepted message, measured in UTF-8 bytes. */
  public static final int MAX_MESSAGE_BYTES = 32 * 1024;

  private static final Logger LOG = LoggerFactory.getLogger(AgentInvoker.class);

  private final AgentService agent;

  public AgentInvoker(@Qualifier("springAiAgentService") AgentService agent) {
    this.agent = agent;
  }

  /** Throws {@link InvalidRequestException} if the message is missing, blank or too large. */
  static void requireMessage(String message) {
    if (message == null || message.isBlank()) {
      throw new InvalidRequestException("message is required");
    }
    if (message.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
      throw new InvalidRequestException("message must not exceed 32768 bytes");
    }
  }

  String send(String channel, String userId, String profile, String message) {
    requireMessage(message);
    try {
      SessionIds.of(channel, userId, profile);
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException("Invalid session identity");
    }
    try {
      return agent.process(channel, userId, profile, message);
    } catch (ProfileNotFoundException | ModelUnavailableException | ModelTimeoutException known) {
      throw known;
    } catch (IllegalArgumentException internal) {
      LOG.error("Agent run rejected already validated input", internal);
      throw new IllegalStateException("Agent run failed");
    }
  }
}
