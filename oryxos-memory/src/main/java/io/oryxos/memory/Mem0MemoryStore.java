package io.oryxos.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.memory.LongTermMemoryStore;
import io.oryxos.core.memory.MemoryEntry;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryStoreException;
import io.oryxos.core.memory.MemoryText;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * HTTP adapter for a Mem0 REST server.
 *
 * <p><b>Unverified against a real Mem0.</b> The request/response shapes below follow Mem0's
 * documented REST API as best known (POST /memories, GET /memories?user_id=) and are exercised only
 * against a fake server in tests. Check them against your deployment before relying on this.
 *
 * <p>Design choices that keep the {@link LongTermMemoryStore} contract: facts are sent with {@code
 * infer:false} so Mem0 stores them verbatim instead of extracting its own "memories" from the text;
 * CORE/ARCHIVAL travels in {@code metadata.scope}; reads list by user and filter locally, because
 * Mem0's own search is semantic while this contract is keyword-based.
 */
public final class Mem0MemoryStore implements LongTermMemoryStore {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final HttpClient client;
  private final URI baseUri;
  private final String userId;
  private final String apiKey;
  private final Clock clock;

  public Mem0MemoryStore(
      HttpClient client, String baseUrl, String userId, String apiKey, Clock clock) {
    this.client = Objects.requireNonNull(client, "client");
    this.baseUri = parseBase(baseUrl);
    if (userId == null || userId.isBlank()) {
      throw new IllegalArgumentException("mem0 user id is required");
    }
    this.userId = userId;
    this.apiKey = apiKey == null ? "" : apiKey;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  private static URI parseBase(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("mem0 base URL is required");
    }
    URI uri = URI.create(baseUrl.strip().replaceAll("/+$", ""));
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if ((!"http".equals(scheme) && !"https".equals(scheme)) || uri.getHost() == null) {
      throw new IllegalArgumentException("mem0 base URL must be http(s)://host[:port]");
    }
    return uri;
  }

  @Override
  public void save(MemoryScope scope, String content) {
    Objects.requireNonNull(scope, "scope");
    String text = MemoryText.normalize(content);
    ObjectNode body = MAPPER.createObjectNode();
    ArrayNode messages = body.putArray("messages");
    messages.addObject().put("role", "user").put("content", text);
    body.put("user_id", userId);
    body.put("infer", false);
    body.putObject("metadata")
        .put("scope", scope.name())
        .put("saved_on", LocalDate.now(clock).toString());
    send(request("/memories").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
  }

  @Override
  public List<MemoryEntry> readCore() {
    return list(MemoryScope.CORE, null);
  }

  @Override
  public List<MemoryEntry> readArchival(int limit) {
    return tail(list(MemoryScope.ARCHIVAL, null), limit);
  }

  @Override
  public List<MemoryEntry> searchArchival(String keyword, int limit) {
    String needle = MemoryText.keyword(keyword).toLowerCase(Locale.ROOT);
    return tail(list(MemoryScope.ARCHIVAL, needle), limit);
  }

  private record Item(String createdAt, int position, MemoryEntry entry) {}

  private List<MemoryEntry> list(MemoryScope wanted, String lowerNeedle) {
    String path = "/memories?user_id=" + URLEncoder.encode(userId, StandardCharsets.UTF_8);
    JsonNode root = parse(send(request(path).GET().build()));
    JsonNode array = root.isArray() ? root : root.path("results");
    List<Item> items = new ArrayList<>();
    int position = 0;
    for (JsonNode node : array) {
      String text =
          node.hasNonNull("memory") ? node.get("memory").asText() : node.path("text").asText("");
      if (text.isBlank()) {
        position++;
        continue;
      }
      // Memories not written by us carry no scope: treat them as ARCHIVAL, never as CORE.
      String scopeName = node.path("metadata").path("scope").asText("ARCHIVAL");
      if (!wanted.name().equals(scopeName)) {
        position++;
        continue;
      }
      if (lowerNeedle != null && !text.toLowerCase(Locale.ROOT).contains(lowerNeedle)) {
        position++;
        continue;
      }
      items.add(
          new Item(
              node.path("created_at").asText(""),
              position++,
              new MemoryEntry(
                  wanted, text, date(node.path("metadata").path("saved_on").asText("")))));
    }
    // Oldest first: by server timestamp when present, otherwise by response order.
    items.sort(Comparator.comparing(Item::createdAt).thenComparingInt(Item::position));
    return items.stream().map(Item::entry).toList();
  }

  private static LocalDate date(String raw) {
    try {
      return raw.isBlank() ? null : LocalDate.parse(raw);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static List<MemoryEntry> tail(List<MemoryEntry> all, int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive");
    }
    return all.size() <= limit ? all : List.copyOf(all.subList(all.size() - limit, all.size()));
  }

  private HttpRequest.Builder request(String pathAndQuery) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(baseUri + pathAndQuery))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json");
    if (!apiKey.isBlank()) {
      builder.header("Authorization", "Token " + apiKey);
    }
    return builder;
  }

  private String send(HttpRequest request) {
    try {
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() / 100 != 2) {
        throw new MemoryStoreException("Mem0 returned HTTP " + response.statusCode());
      }
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MemoryStoreException("Mem0 request interrupted", e);
    } catch (IOException e) {
      throw new MemoryStoreException("Mem0 request failed: " + e.getClass().getSimpleName(), e);
    }
  }

  private static JsonNode parse(String body) {
    try {
      return MAPPER.readTree(body == null || body.isBlank() ? "[]" : body);
    } catch (JsonProcessingException e) {
      throw new MemoryStoreException("Mem0 returned invalid JSON", e);
    }
  }
}
