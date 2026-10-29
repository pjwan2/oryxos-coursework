package io.oryxos.tool.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.sandbox.Sandbox;
import io.oryxos.core.sandbox.SandboxViolation;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * POSTs {"title","text"} JSON to the target URL. The Sandbox host check happens BEFORE any network
 * I/O. Only a 2xx response counts as delivered; redirects are not followed.
 */
public final class WebhookNotifyAdapter implements NotifyChannelAdapter {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final HttpClient client;

  public WebhookNotifyAdapter(HttpClient client) {
    this.client = Objects.requireNonNull(client, "client");
    if (client.followRedirects() != HttpClient.Redirect.NEVER) {
      throw new IllegalArgumentException("HttpClient must not follow redirects");
    }
  }

  @Override
  public String type() {
    return "webhook";
  }

  @Override
  public NotifyResult send(Sandbox sandbox, NotifyTarget target, String title, String message) {
    try {
      URI uri = URI.create(target.url());
      sandbox.checkHttpTarget(uri);
      Map<String, String> payload = new LinkedHashMap<>();
      payload.put("title", title);
      payload.put("text", message);
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "application/json; charset=utf-8")
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
              .build();
      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
      int status = response.statusCode();
      if (status / 100 == 2) {
        return NotifyResult.ok();
      }
      return NotifyResult.failed("Webhook returned HTTP " + status, status >= 500);
    } catch (SandboxViolation denied) {
      return NotifyResult.failed("SANDBOX_DENIED: " + denied.getMessage(), false);
    } catch (IllegalArgumentException badUrl) {
      return NotifyResult.failed("Invalid webhook URL", false);
    } catch (JsonProcessingException e) {
      return NotifyResult.failed("Unable to encode payload", false);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return NotifyResult.failed("Webhook interrupted", true);
    } catch (IOException io) {
      return NotifyResult.failed("Webhook request failed: " + io.getClass().getSimpleName(), true);
    }
  }
}
