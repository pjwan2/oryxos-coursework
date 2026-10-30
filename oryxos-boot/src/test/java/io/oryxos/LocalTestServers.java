package io.oryxos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Loopback servers for the end-to-end tests: no API key, no public network.
 *
 * <p>{@link FakeModelServer} speaks the OpenAI chat-completions protocol, so the REAL Spring AI
 * client, {@code ProviderService}, ReAct loop, tool executor and SQLite audit run unchanged; only
 * the model's answer is scripted. It counts requests on its own, which is independent evidence of
 * how many model calls were made. {@link WeatherFixture} is the "weather API" the tool fetches.
 */
final class LocalTestServers {
  static final ObjectMapper JSON = new ObjectMapper();

  private LocalTestServers() {}

  /** What the scripted model answers: plain text, or a request to call one tool. */
  record Decision(String text, String toolName, String toolArgumentsJson) {
    static Decision say(String text) {
      return new Decision(text, null, null);
    }

    static Decision call(String toolName, String argumentsJson) {
      return new Decision(null, toolName, argumentsJson);
    }
  }

  static final class FakeModelServer implements AutoCloseable {
    private final HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();

    /** {@code brain} sees each request body; {@code delayMillis} makes the "model" slow. */
    FakeModelServer(Function<JsonNode, Decision> brain, long delayMillis) {
      try {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      server.setExecutor(Executors.newCachedThreadPool());
      server.createContext(
          "/v1/chat/completions",
          exchange -> {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            calls.incrementAndGet();
            requests.add(request);
            if (delayMillis > 0) {
              try {
                Thread.sleep(delayMillis);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
            byte[] body = JSON.writeValueAsBytes(completion(brain.apply(request)));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
    }

    private static ObjectNode completion(Decision decision) {
      ObjectNode message = JSON.createObjectNode().put("role", "assistant");
      String finish;
      if (decision.toolName() == null) {
        message.put("content", decision.text());
        finish = "stop";
      } else {
        message.putNull("content");
        message
            .putArray("tool_calls")
            .addObject()
            .put("id", "call_" + decision.toolName())
            .put("type", "function")
            .putObject("function")
            .put("name", decision.toolName())
            .put("arguments", decision.toolArgumentsJson());
        finish = "tool_calls";
      }
      ObjectNode root = JSON.createObjectNode();
      root.put("id", "chatcmpl-test")
          .put("object", "chat.completion")
          .put("created", 0)
          .put("model", "fake-model");
      root.putArray("choices")
          .addObject()
          .put("index", 0)
          .put("finish_reason", finish)
          .set("message", message);
      root.putObject("usage")
          .put("prompt_tokens", 11)
          .put("completion_tokens", 7)
          .put("total_tokens", 18);
      return root;
    }

    String baseUrl() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int requestCount() {
      return calls.get();
    }

    List<JsonNode> requests() {
      return List.copyOf(requests);
    }

    void reset() {
      calls.set(0);
      requests.clear();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  static final class WeatherFixture implements AutoCloseable {
    static final String BODY = "{\"city\":\"Shanghai\",\"temp_c\":21,\"sky\":\"clear\"}";
    private final HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    WeatherFixture() {
      try {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      server.createContext(
          "/weather",
          exchange -> {
            hits.incrementAndGet();
            byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/weather";
    }

    int hits() {
      return hits.get();
    }

    void reset() {
      hits.set(0);
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  /** Text of the last message of a chat-completions request, and its role. */
  static String lastRole(JsonNode request) {
    JsonNode messages = request.get("messages");
    return messages.get(messages.size() - 1).path("role").asText();
  }

  static String lastContent(JsonNode request) {
    JsonNode messages = request.get("messages");
    return messages.get(messages.size() - 1).path("content").asText("");
  }

  static String systemContent(JsonNode request) {
    for (JsonNode message : request.get("messages")) {
      if ("system".equals(message.path("role").asText())) {
        return message.path("content").asText("");
      }
    }
    return "";
  }
}
