package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.cli.OryxCli;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CLI -> AgentService -> ReAct -> Tool -> SQLite with the offline mock model. Each run() starts its
 * own Spring context against the same database file, which also proves restart recovery.
 */
class CliEndToEndTest {
  @TempDir Path temp;
  private Path db;
  private Path profiles;
  private final StringWriter out = new StringWriter();
  private final StringWriter err = new StringWriter();

  @BeforeEach
  void setUp() throws Exception {
    db = temp.resolve("e2e.db");
    profiles = Files.createDirectory(temp.resolve("profiles"));
    Files.writeString(
        profiles.resolve("agent-a.yaml"), profile("agent-a", "tools:\n  - get_payment\n"));
    Files.writeString(profiles.resolve("agent-b.yaml"), profile("agent-b", ""));
  }

  private static String profile(String name, String tools) {
    return "name: "
        + name
        + "\ndescription: e2e\nidentity:\n  prompt: test agent\n"
        + "provider:\n  name: mock\n  model: mock-echo\n  temperature: 0.2\n"
        + tools
        + "settings:\n  max_iterations: 5\n";
  }

  private int run(String... args) {
    var runtime =
        new BootCliRuntime(
            Map.of(
                "spring.datasource.url",
                "jdbc:sqlite:" + db.toString().replace('\\', '/'),
                "oryxos.profile-directory",
                profiles.toString()));
    return OryxCli.create(
            runtime,
            new PrintWriter(out, true),
            new PrintWriter(err, true),
            new ByteArrayInputStream(new byte[0]))
        .execute(args);
  }

  private String scalar(String sql) throws SQLException {
    try (Connection c =
            DriverManager.getConnection("jdbc:sqlite:" + db.toString().replace('\\', '/'));
        var st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  @Test
  void toolCallRunsThroughReActAndLeavesSessionAndAuditRows() throws Exception {
    int code =
        run(
            "chat",
            "agent-a",
            "--user",
            "u1",
            "/tool",
            "get_payment",
            "{\"payment_id\":\"P1001\"}");
    assertEquals(0, code, err.toString());
    assertTrue(out.toString().contains("Tool result: P1001 PENDING 120 AUD"), out.toString());

    assertEquals("1", scalar("select count(*) from sessions where session_id='cli:u1:agent-a'"));
    assertEquals(
        "get_payment|1",
        scalar(
            "select tool_name||'|'||success from tool_invocations where session_id='cli:u1:agent-a'"));
    // one model call asking for the tool, one more to phrase the answer
    assertEquals("2", scalar("select count(*) from llm_calls where session_id='cli:u1:agent-a'"));
    assertEquals(
        "1", scalar("select min(success) from llm_calls where session_id='cli:u1:agent-a'"));
  }

  @Test
  void unknownPaymentIsAuditedAsAFailure() throws Exception {
    assertEquals(
        0,
        run(
            "chat",
            "agent-a",
            "--user",
            "u2",
            "/tool",
            "get_payment",
            "{\"payment_id\":\"P9999\"}"));
    assertTrue(out.toString().contains("ERROR Payment not found"), out.toString());
    assertEquals(
        "0|ToolReturnedFailure",
        scalar(
            "select success||'|'||error_category from tool_invocations"
                + " where session_id='cli:u2:agent-a'"));
  }

  @Test
  void sessionSurvivesRestartAndKeepsGrowing() throws Exception {
    assertEquals(0, run("chat", "agent-a", "--user", "u3", "first question"));
    String after1 = scalar("select messages_json from sessions where session_id='cli:u3:agent-a'");
    assertTrue(after1.contains("first question"));

    // New runtime = new Spring context: the history must come back from SQLite.
    assertEquals(0, run("chat", "agent-a", "--user", "u3", "second question"));
    String after2 = scalar("select messages_json from sessions where session_id='cli:u3:agent-a'");
    assertTrue(after2.contains("first question") && after2.contains("second question"));
    assertEquals("1", scalar("select count(*) from sessions where session_id='cli:u3:agent-a'"));
  }

  @Test
  void agentsAreIsolatedByProfileAllowlistAndSession() throws Exception {
    assertEquals(
        0,
        run(
            "chat",
            "agent-b",
            "--user",
            "u4",
            "/tool",
            "get_payment",
            "{\"payment_id\":\"P1001\"}"));
    // agent-b lists no tools, so the model is never offered get_payment and nothing executes
    assertTrue(out.toString().contains("Tool not available: get_payment"), out.toString());
    assertEquals(
        "0", scalar("select count(*) from tool_invocations where session_id='cli:u4:agent-b'"));
    assertEquals("0", scalar("select count(*) from sessions where session_id='cli:u4:agent-a'"));
  }

  @Test
  void unknownProfileIsReportedWithoutStackTraceAndExitCode2() {
    assertEquals(2, run("chat", "ghost", "hello"));
    assertTrue(err.toString().startsWith("Error: "), err.toString());
    assertFalse(err.toString().contains("\tat "));
  }

  @Test
  void lightCommandsDoNotStartSpringOrCreateTheDatabase() {
    assertEquals(0, run("status"));
    assertEquals(0, run("profile", "list"));
    assertEquals(0, run("tool", "list"));
    assertFalse(Files.exists(db), "light commands must not open the database");
    String text = out.toString();
    assertTrue(text.contains("agent-a  mock/mock-echo  tools=[get_payment]"), text);
    assertTrue(text.contains("agent-b  mock/mock-echo  tools=[]"), text);
    assertTrue(text.contains("shell_exec") && text.contains("notify"), text);
    assertTrue(text.contains("(not created yet)"), text);
  }
}
