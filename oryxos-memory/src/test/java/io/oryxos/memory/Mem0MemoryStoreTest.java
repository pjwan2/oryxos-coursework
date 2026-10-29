package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.oryxos.core.memory.LongTermMemoryStore;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryStoreException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Runs the shared contract against a FAKE Mem0 server (JDK HttpServer). This proves the adapter
 * sends and parses the shapes it assumes; it does NOT prove compatibility with a real Mem0.
 */
class Mem0MemoryStoreTest extends MemoryStoreContractTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Minimal in-memory stand-in: POST /memories and GET /memories?user_id=. */
  private static final class FakeMem0 {
    final HttpServer server;
    final List<ObjectNode> memories = new ArrayList<>();
    final List<String> postBodies = new CopyOnWriteArrayList<>();
    final List<String> authHeaders = new CopyOnWriteArrayList<>();
    volatile int failWith;
    private int counter;

    FakeMem0() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/memories", this::handle);
      server.start();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private synchronized void handle(HttpExchange ex) throws IOException {
      authHeaders.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
      if (failWith != 0) {
        ex.sendResponseHeaders(failWith, -1);
        ex.close();
        return;
      }
      if ("POST".equals(ex.getRequestMethod())) {
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        postBodies.add(raw);
        JsonNode body = MAPPER.readTree(raw);
        ObjectNode stored = MAPPER.createObjectNode();
        stored.put("id", "m" + counter);
        stored.put("memory", body.path("messages").path(0).path("content").asText());
        stored.put("user_id", body.path("user_id").asText());
        stored.set("metadata", body.path("metadata"));
        stored.put("created_at", String.format("2026-03-04T10:00:00.%06d", counter++));
        memories.add(stored);
        reply(
            ex, "{\"results\":[{\"id\":\"" + stored.get("id").asText() + "\",\"event\":\"ADD\"}]}");
        return;
      }
      String query = ex.getRequestURI().getRawQuery();
      String user =
          URLDecoder.decode(query.substring(query.indexOf('=') + 1), StandardCharsets.UTF_8);
      ArrayNode out = MAPPER.createArrayNode();
      List<ObjectNode> mine = new ArrayList<>();
      for (ObjectNode m : memories) {
        if (user.equals(m.get("user_id").asText())) {
          mine.add(m);
        }
      }
      Collections.reverse(mine); // a real server gives no ordering promise: newest first here
      mine.forEach(out::add);
      reply(ex, MAPPER.createObjectNode().set("results", out).toString());
    }

    private void reply(HttpExchange ex, String json) throws IOException {
      byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, bytes.length);
      ex.getResponseBody().write(bytes);
      ex.close();
    }
  }

  private FakeMem0 fake;

  @AfterEach
  void stop() {
    if (fake != null) {
      fake.server.stop(0);
    }
  }

  private LongTermMemoryStore store(String userId, String apiKey) {
    return new Mem0MemoryStore(HttpClient.newHttpClient(), fake.url(), userId, apiKey, FIXED);
  }

  @Override
  protected LongTermMemoryStore create() throws Exception {
    fake = new FakeMem0();
    return store("oryxos", "");
  }

  @Override
  protected LongTermMemoryStore reopen() {
    return store("oryxos", "");
  }

  @Test
  void saveSendsVerbatimTextWithoutInferenceAndTheScopeAsMetadata() throws Exception {
    LongTermMemoryStore store = create();
    store.save(MemoryScope.CORE, "name is Alex");
    JsonNode sent = MAPPER.readTree(fake.postBodies.getFirst());
    assertEquals("name is Alex", sent.path("messages").path(0).path("content").asText());
    assertEquals("user", sent.path("messages").path(0).path("role").asText());
    assertEquals("oryxos", sent.path("user_id").asText());
    assertFalse(sent.path("infer").asBoolean(true), "Mem0 must not rewrite the fact");
    assertEquals("CORE", sent.path("metadata").path("scope").asText());
    assertEquals("2026-03-04", sent.path("metadata").path("saved_on").asText());
  }

  @Test
  void apiKeyIsSentOnlyWhenConfigured() throws Exception {
    create();
    store("oryxos", "").readCore();
    store("oryxos", "secret-key").readCore();
    assertEquals("null", fake.authHeaders.get(0));
    assertEquals("Token secret-key", fake.authHeaders.get(1));
  }

  @Test
  void usersAreIsolatedByUserId() throws Exception {
    create();
    store("alice", "").save(MemoryScope.ARCHIVAL, "alice fact");
    store("bob", "").save(MemoryScope.ARCHIVAL, "bob fact");
    assertEquals(
        List.of("alice fact"),
        store("alice", "").readArchival(10).stream().map(e -> e.content()).toList());
  }

  @Test
  void memoriesWithoutOurScopeAreArchivalNeverCore() throws Exception {
    create();
    ObjectNode foreign = MAPPER.createObjectNode();
    foreign.put("id", "x");
    foreign.put("memory", "written by another tool");
    foreign.put("user_id", "oryxos");
    foreign.put("created_at", "2020-01-01T00:00:00");
    fake.memories.add(foreign);
    LongTermMemoryStore store = store("oryxos", "");
    assertTrue(store.readCore().isEmpty());
    assertEquals("written by another tool", store.readArchival(5).getFirst().content());
  }

  @Test
  void serverErrorsAndUnreachableServerAreMemoryStoreExceptions() throws Exception {
    LongTermMemoryStore store = create();
    fake.failWith = 500;
    assertThrows(MemoryStoreException.class, () -> store.save(MemoryScope.CORE, "x"));
    assertThrows(MemoryStoreException.class, store::readCore);

    fake.server.stop(0);
    assertThrows(MemoryStoreException.class, () -> store.readArchival(5));
  }

  @Test
  void garbageResponseIsReportedNotSwallowed() throws Exception {
    create();
    fake.server.removeContext("/memories");
    fake.server.createContext(
        "/memories",
        ex -> {
          byte[] junk = "<html>not json".getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, junk.length);
          ex.getResponseBody().write(junk);
          ex.close();
        });
    assertThrows(MemoryStoreException.class, () -> store("oryxos", "").readCore());
  }

  @Test
  void constructorRejectsBadConfiguration() {
    var client = HttpClient.newHttpClient();
    assertThrows(
        IllegalArgumentException.class, () -> new Mem0MemoryStore(client, "", "u", "", FIXED));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Mem0MemoryStore(client, "ftp://host", "u", "", FIXED));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Mem0MemoryStore(client, "http://host", " ", "", FIXED));
  }
}
