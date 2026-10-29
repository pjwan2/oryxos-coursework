package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.ModelTimeoutException;
import io.oryxos.core.agent.ModelUnavailableException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/** The REST layer's 503/504 depend on this sorting of provider failures. */
class ModelFailuresTest {
  @Test
  void socketAndHttpTimeoutsAreTimeoutsEvenWhenWrapped() {
    var wrapped =
        new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out"));
    assertInstanceOf(ModelTimeoutException.class, ModelFailures.classify("p", wrapped));
    var http = new RuntimeException("outer", new HttpTimeoutException("request timed out"));
    assertInstanceOf(ModelTimeoutException.class, ModelFailures.classify("p", http));
  }

  @Test
  void gatewayTimeoutStatusIsATimeout() {
    var gatewayTimeout =
        HttpServerErrorException.create(HttpStatus.GATEWAY_TIMEOUT, "x", null, null, null);
    assertInstanceOf(ModelTimeoutException.class, ModelFailures.classify("p", gatewayTimeout));
  }

  @Test
  void connectionProblemsAndOverloadAreUnavailable() {
    var refused = new ResourceAccessException("I/O error", new ConnectException("refused"));
    assertInstanceOf(ModelUnavailableException.class, ModelFailures.classify("p", refused));
    assertInstanceOf(
        ModelUnavailableException.class,
        ModelFailures.classify("p", new TransientAiException("429")));
    var server = HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "x", null, null, null);
    assertInstanceOf(ModelUnavailableException.class, ModelFailures.classify("p", server));
  }

  @Test
  void otherFailuresAreLeftUntouched() {
    RuntimeException rejected = new NonTransientAiException("401 invalid key sk-secret");
    assertSame(rejected, ModelFailures.classify("p", rejected));
    RuntimeException plain = new IllegalStateException("boom");
    assertSame(plain, ModelFailures.classify("p", plain));
  }

  @Test
  void theProviderMessageIsNeverCarried() {
    RuntimeException translated =
        ModelFailures.classify(
            "deepseek",
            new ResourceAccessException("secret-token", new ConnectException("secret-token")));
    assertFalse(translated.getMessage().contains("secret-token"));
    assertTrue(translated.getMessage().contains("deepseek"));
    assertSame(null, translated.getCause());
  }
}
