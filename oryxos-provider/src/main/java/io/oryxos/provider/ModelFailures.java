package io.oryxos.provider;

import io.oryxos.core.agent.ModelTimeoutException;
import io.oryxos.core.agent.ModelUnavailableException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Sorts a provider failure into "too slow" and "not reachable" so callers (the REST layer) can
 * answer 504 and 503. Anything else, such as a rejected API key or a malformed answer, is left
 * untouched and stays a generic failure. The original message is dropped on purpose.
 */
final class ModelFailures {
  private static final int MAX_DEPTH = 8;

  private ModelFailures() {}

  static RuntimeException classify(String providerName, RuntimeException failure) {
    boolean unavailable = false;
    Throwable current = failure;
    for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
      if (isTimeout(current)) {
        return new ModelTimeoutException(providerName);
      }
      unavailable |= isUnreachable(current);
      current = current.getCause() == current ? null : current.getCause();
    }
    return unavailable ? new ModelUnavailableException(providerName) : failure;
  }

  private static boolean isTimeout(Throwable t) {
    if (t instanceof RestClientResponseException http) {
      int status = http.getStatusCode().value();
      return status == 408 || status == 504;
    }
    return t instanceof SocketTimeoutException
        || t instanceof HttpTimeoutException
        || t instanceof HttpConnectTimeoutException;
  }

  private static boolean isUnreachable(Throwable t) {
    if (t instanceof RestClientResponseException http) {
      int status = http.getStatusCode().value();
      return status == 429 || status >= 500;
    }
    return t instanceof ConnectException
        || t instanceof UnknownHostException
        || t instanceof TransientAiException
        || t instanceof ResourceAccessException
        || t instanceof IOException;
  }
}
