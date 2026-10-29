package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.cli.OryxCli;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Each run() starts its own Spring context and closes it, the closest in-JVM equivalent of a
 * process restart: memory written by one run must be read by the next, for both backends.
 */
class MemoryRestartTest {
  @TempDir Path temp;

  private String run(String backend, String... args) {
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    var runtime =
        new BootCliRuntime(
            Map.of(
                "oryxos.memory.backend",
                backend,
                "oryxos.memory.markdown-path",
                temp.resolve("MEMORY.md").toString(),
                "spring.datasource.url",
                "jdbc:sqlite:" + temp.resolve("restart.db").toString().replace('\\', '/'),
                "oryxos.profile-directory",
                Path.of("..", ".oryxos", "profiles").toString()));
    int code =
        OryxCli.create(
                runtime,
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                new ByteArrayInputStream(new byte[0]))
            .execute(args);
    assertEquals(0, code, err.toString());
    return out.toString();
  }

  private void assertSurvivesRestart(String backend) {
    run(
        backend,
        "chat",
        "memory-demo",
        "--user",
        "r1",
        "tool:save_memory",
        "{\"content\":\"restart-core-fact\",\"scope\":\"CORE\"}");
    run(
        backend,
        "chat",
        "memory-demo",
        "--user",
        "r1",
        "tool:save_memory",
        "{\"content\":\"restart-archive-fact\"}");

    // brand-new Spring context, brand-new user/session
    String recalled =
        run(
            backend,
            "chat",
            "memory-demo",
            "--user",
            "someone-else",
            "tool:recall_memory",
            "{\"keyword\":\"restart\"}");
    assertTrue(recalled.contains("restart-archive-fact"), recalled);
    assertTrue(!recalled.contains("restart-core-fact"), "CORE is not part of recall: " + recalled);
  }

  @Test
  void markdownMemorySurvivesARestart() throws Exception {
    assertSurvivesRestart("markdown");
    String raw = Files.readString(temp.resolve("MEMORY.md"));
    assertTrue(raw.contains("restart-core-fact") && raw.contains("restart-archive-fact"));
  }

  @Test
  void sqliteMemorySurvivesARestart() {
    assertSurvivesRestart("sqlite");
    assertTrue(Files.exists(temp.resolve("restart.db")));
  }

  @Test
  void anUnknownBackendFailsFastWithAClearMessage() {
    StringWriter err = new StringWriter();
    var runtime =
        new BootCliRuntime(
            Map.of(
                "oryxos.memory.backend",
                "redis",
                "spring.datasource.url",
                "jdbc:sqlite:" + temp.resolve("x.db").toString().replace('\\', '/'),
                "oryxos.profile-directory",
                Path.of("..", ".oryxos", "profiles").toString()));
    int code =
        OryxCli.create(
                runtime,
                new PrintWriter(new StringWriter(), true),
                new PrintWriter(err, true),
                new ByteArrayInputStream(new byte[0]))
            .execute("chat", "memory-demo", "hi");
    assertEquals(1, code);
    assertTrue(err.toString().startsWith("Error: "), err.toString());
  }
}
