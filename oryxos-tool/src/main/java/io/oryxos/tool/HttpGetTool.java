package io.oryxos.tool;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.sandbox.Sandbox;
import io.oryxos.core.sandbox.SandboxViolation;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** GET against allowlisted hosts. Redirects are never followed (they could leave the allowlist). */
public final class HttpGetTool implements OryxTool {
  static final int MAX_BYTES = 64 * 1024;
  private final Sandbox sandbox;
  private final HttpClient client;

  public HttpGetTool(Sandbox sandbox, HttpClient client) {
    this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
    this.client = Objects.requireNonNull(client, "client");
  }

  public static HttpClient defaultClient() {
    return HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  @Override
  public String getName() {
    return "http_get";
  }

  @Override
  public String getDescription() {
    return "HTTP GET a URL on an allowed host";
  }

  @Override
  public String getInputSchema() {
    return "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}},"
        + "\"required\":[\"url\"]}";
  }

  @Override
  public ToolOutput execute(String argumentsJson, ToolContext context) {
    try {
      JsonNode args = ToolArgs.parse(argumentsJson);
      URI uri = URI.create(ToolArgs.requireText(args, "url"));
      sandbox.checkUri(uri);
      HttpRequest request =
          HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(10)).build();
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        String text = new String(body.readNBytes(MAX_BYTES), StandardCharsets.UTF_8);
        int status = response.statusCode();
        if (status / 100 != 2) {
          return ToolOutput.failed("HTTP status " + status, status >= 500);
        }
        return ToolOutput.ok(text);
      }
    } catch (SandboxViolation denied) {
      return ToolOutput.denied(denied.getMessage());
    } catch (IllegalArgumentException invalid) {
      return ToolOutput.failed(invalid.getMessage(), false);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return ToolOutput.failed("Request interrupted", true);
    } catch (IOException io) {
      return ToolOutput.failed("HTTP request failed: " + io.getClass().getSimpleName(), true);
    }
  }
}
