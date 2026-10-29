package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * Failsafe integration test (runs in {@code mvn verify}, after the fat JAR has been repackaged). It
 * starts the real JAR as a separate JVM on a free loopback port, with its own profiles and SQLite
 * file, and talks to it over HTTP: the admin console, a client-side route, the REST API, a
 * scheduler run, and finally a restart to prove that sessions and tasks survive it.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebSmokeIT {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  @TempDir static Path work;

  private static Path jar;
  private static int port;
  private static Process server;
  private static Path log;

  @BeforeAll
  static void prepare() throws IOException {
    jar = fatJar();
    Path profiles = Files.createDirectories(work.resolve("profiles"));
    Files.createDirectories(work.resolve("ws"));
    Files.writeString(
        profiles.resolve("smoke.yaml"),
        "name: smoke\n"
            + "description: web smoke profile\n"
            + "provider: {name: mock, model: m, temperature: 0.2}\n"
            + "tools: [file_read]\n"
            + "settings:\n  max_iterations: 5\n"
            + "schedules:\n"
            + "  - id: nightly\n"
            + "    cron: \"0 0 3 1 1 *\"\n"
            + "    zone: UTC\n"
            + "    message: 'scheduled hello'\n");
    port = freePort();
  }

  @AfterAll
  static void stop() {
    stopServer();
  }

  // ---- the artifact ---------------------------------------------------------------------

  @Test
  @Order(1)
  void theFatJarContainsTheVueBuild() throws IOException {
    boolean shell = false;
    boolean asset = false;
    try (JarFile outer = new JarFile(jar.toFile())) {
      var libs =
          outer.stream().filter(e -> e.getName().startsWith("BOOT-INF/lib/oryxos-web-")).toList();
      assertEquals(1, libs.size(), "the web module is nested in the fat jar");
      Path nested = Files.createTempFile(work, "oryxos-web", ".jar");
      try (var in = outer.getInputStream(libs.getFirst())) {
        Files.copy(in, nested, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      try (JarFile web = new JarFile(nested.toFile())) {
        shell = web.getEntry("static/admin/index.html") != null;
        asset =
            web.stream().anyMatch(e -> e.getName().matches("static/admin/assets/index-.*\\.js"));
      }
    }
    assertTrue(shell, "static/admin/index.html is inside the web module jar");
    assertTrue(asset, "the bundled script is inside the web module jar");
  }

  // ---- running it -----------------------------------------------------------------------

  @Test
  @Order(2)
  void theJarStartsAndAnswersHealthOnLoopback() throws Exception {
    start();
    JsonNode health = get("/api/v1/health").json().get("data");
    assertEquals("ok", health.get("status").asText());
    assertEquals("127.0.0.1", get("/api/v1/info").json().get("data").get("bindAddress").asText());
  }

  @Test
  @Order(3)
  void theConsoleAndItsClientRoutesAreServed() throws Exception {
    Reply shell = get("/admin/");
    assertEquals(200, shell.status());
    assertTrue(shell.header("content-type").startsWith("text/html"));
    assertTrue(shell.body().contains("<div id=\"app\"></div>"));

    for (String route :
        List.of("/admin/sessions", "/admin/scheduler", "/admin/sessions/web:u1:smoke")) {
      Reply reload = get(route);
      assertEquals(200, reload.status(), route);
      assertTrue(reload.body().contains("<div id=\"app\"></div>"), route);
    }
    String script = shell.body().replaceAll("(?s).*src=\"(/admin/assets/[^\"]+\\.js)\".*", "$1");
    assertEquals(200, get(script).status());
    assertEquals(404, get("/admin/assets/nope.js").status());
    assertEquals("application/json", get("/api/v1/nope").header("content-type").split(";")[0]);
    assertEquals(404, get("/api/v1/nope").status());
  }

  @Test
  @Order(4)
  void sessionsAndSchedulesWorkOverRealHttp() throws Exception {
    JsonNode created =
        post("/api/v1/sessions", "{\"profile\":\"smoke\",\"userId\":\"smoke-user\"}").json();
    assertEquals("web:smoke-user:smoke", created.get("data").get("sessionId").asText());
    JsonNode reply =
        post("/api/v1/sessions/web:smoke-user:smoke/messages", "{\"content\":\"ping\"}").json();
    assertEquals("Mock response: ping", reply.get("data").get("reply").asText());

    JsonNode run = post("/api/v1/schedules/smoke.nightly/run", null).json().get("data");
    assertTrue(run.get("success").asBoolean());
    assertEquals("manual", run.get("trigger").asText());

    JsonNode off = put("/api/v1/schedules/smoke.nightly", "{\"enabled\":false}").json().get("data");
    assertFalse(off.get("enabled").asBoolean());
    assertEquals(1, off.get("runCount").asInt());
  }

  @Test
  @Order(5)
  void sessionsAndTasksSurviveARestart() throws Exception {
    assertEquals(2, sessionIds().size(), "web session and scheduler session before the restart");
    stopServer();
    start();

    List<String> after = sessionIds();
    assertTrue(after.contains("web:smoke-user:smoke"));
    assertTrue(after.contains("scheduler:scheduler:smoke"));
    JsonNode detail = get("/api/v1/sessions/web:smoke-user:smoke").json().get("data");
    assertEquals(2, detail.get("totalMessages").asInt());
    assertEquals("ping", detail.get("messages").get(0).get("content").asText());

    JsonNode task = get("/api/v1/schedules").json().get("data").get(0);
    assertEquals("smoke.nightly", task.get("taskId").asText());
    assertFalse(task.get("enabled").asBoolean(), "disabled state is kept");
    assertEquals(1, task.get("runCount").asInt(), "run count is kept");
    assertEquals(1, get("/api/v1/schedules/smoke.nightly/executions").json().get("data").size());
  }

  private static List<String> sessionIds() throws Exception {
    List<String> ids = new ArrayList<>();
    get("/api/v1/sessions").json().get("data").forEach(s -> ids.add(s.get("sessionId").asText()));
    return ids;
  }

  // ---- process and HTTP helpers ---------------------------------------------------------

  private static Path fatJar() throws IOException {
    try (Stream<Path> files = Files.list(Path.of("target"))) {
      return files
          .filter(p -> p.getFileName().toString().matches("oryxos-boot-.*\\.jar"))
          .filter(p -> !p.getFileName().toString().endsWith(".original"))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("fat jar not found in target/"));
    }
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static void start() throws IOException, InterruptedException {
    log = work.resolve("server-" + System.nanoTime() + ".log");
    String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    ProcessBuilder builder =
        new ProcessBuilder(
            javaBin,
            "-jar",
            jar.toAbsolutePath().toString(),
            "--server.port=" + port,
            "--logging.level.root=WARN",
            "--oryxos.profile-directory=" + slash(work.resolve("profiles")),
            "--spring.datasource.url=jdbc:sqlite:" + slash(work.resolve("smoke.db")),
            "--oryxos.memory.markdown-path=" + slash(work.resolve("MEMORY.md")),
            "--oryxos.sandbox.read-roots=" + slash(work.resolve("ws")),
            "--oryxos.sandbox.write-roots=" + slash(work.resolve("ws")));
    builder.directory(work.toFile());
    builder.redirectErrorStream(true);
    builder.redirectOutput(log.toFile());
    server = builder.start();

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
    while (System.nanoTime() < deadline) {
      if (!server.isAlive()) {
        throw new IllegalStateException("server exited early:\n" + Files.readString(log));
      }
      try {
        if (get("/api/v1/health").status() == 200) {
          return;
        }
      } catch (IOException notYet) {
        // still starting
      }
      Thread.sleep(500);
    }
    throw new IllegalStateException("server did not become healthy:\n" + Files.readString(log));
  }

  private static void stopServer() {
    if (server != null && server.isAlive()) {
      server.destroy();
      try {
        if (!server.waitFor(30, TimeUnit.SECONDS)) {
          server.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        server.destroyForcibly();
      }
    }
  }

  private static String slash(Path path) {
    return path.toString().replace('\\', '/');
  }

  private record Reply(int status, String body, HttpResponse<String> raw) {
    String header(String name) {
      return raw.headers().firstValue(name).orElse("");
    }

    JsonNode json() throws IOException {
      return JSON.readTree(body);
    }
  }

  private static Reply send(HttpRequest.Builder builder) throws IOException {
    try {
      HttpResponse<String> response =
          HTTP.send(
              builder.timeout(Duration.ofSeconds(20)).build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertNotNull(response);
      return new Reply(response.statusCode(), response.body(), response);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }

  private static Reply get(String path) throws IOException {
    return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET());
  }

  private static Reply post(String path, String body) throws IOException {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .POST(
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    return send(builder);
  }

  private static Reply put(String path, String body) throws IOException {
    return send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
  }
}
