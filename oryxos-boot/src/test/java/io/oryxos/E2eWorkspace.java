package io.oryxos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * A throw-away working area for one end-to-end test class: profiles, a sandbox workspace (with an
 * allowed and a forbidden directory), a temporary SQLite file and a temporary Markdown memory file.
 * Nothing here touches the development database or the real {@code .oryxos} directory.
 */
final class E2eWorkspace {
  static final String FAKE_PROVIDER = "fake-openai";
  static final String UNREACHABLE_PROVIDER = "unreachable-openai";

  /** A syntactically valid but meaningless key: it must never appear in any response or table. */
  static final String DUMMY_KEY = "test-key-not-a-real-secret-0123456789";

  final Path root;
  final Path profiles;
  final Path agents;
  final Path allowed;
  final Path forbidden;
  final Path database;
  final Path memoryFile;

  E2eWorkspace(String prefix) {
    try {
      root = Files.createTempDirectory(prefix);
      profiles = Files.createDirectories(root.resolve("profiles"));
      agents = root.resolve("agents");
      allowed = Files.createDirectories(root.resolve("ws/allowed"));
      forbidden = Files.createDirectories(root.resolve("ws/forbidden"));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    database = root.resolve("e2e.db");
    memoryFile = root.resolve("MEMORY.md");
  }

  static String slash(Path path) {
    return path.toString().replace('\\', '/');
  }

  String jdbcUrl() {
    return "jdbc:sqlite:" + slash(database);
  }

  /**
   * Writes a profile. {@code sandbox} is the YAML under {@code settings.sandbox} (two-space
   * indented lines) or null for none; {@code extra} is appended at top level (for example
   * schedules).
   */
  void profile(String name, String provider, List<String> tools, String sandbox, String extra) {
    StringBuilder yaml = new StringBuilder();
    yaml.append("name: ").append(name).append("\ndescription: end-to-end test agent\n");
    yaml.append("provider: {name: ")
        .append(provider)
        .append(", model: test-model, temperature: 0.0}\n");
    yaml.append("tools: [").append(String.join(", ", tools)).append("]\n");
    yaml.append("settings:\n  max_iterations: 5\n");
    if (sandbox != null) {
      yaml.append("  sandbox:\n").append(sandbox);
    }
    if (extra != null) {
      yaml.append(extra);
    }
    try {
      Files.writeString(profiles.resolve(name + ".yaml"), yaml.toString());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Sandbox YAML that allows reading and writing one directory. */
  static String files(Path dir) {
    return "    read_roots: [\"" + slash(dir) + "\"]\n    write_roots: [\"" + slash(dir) + "\"]\n";
  }

  /** Sandbox YAML that allows HTTP to the loopback fixture only. */
  static String loopbackHttp() {
    return "    hosts: [\"127.0.0.1\"]\n    allow_private_networks: true\n";
  }

  /** Spring properties (highest precedence) that point the application at this workspace. */
  Map<String, String> properties(String fakeModelBaseUrl) {
    java.util.LinkedHashMap<String, String> p = new java.util.LinkedHashMap<>();
    p.put("oryxos.profile-directory", slash(profiles));
    p.put("oryxos.agents-directory", slash(agents));
    p.put("spring.datasource.url", jdbcUrl());
    p.put("oryxos.memory.markdown-path", slash(memoryFile));
    p.put("oryxos.sandbox.read-roots", slash(allowed));
    p.put("oryxos.sandbox.write-roots", slash(allowed));
    p.put("oryxos.sandbox.hosts", "127.0.0.1");
    p.put("oryxos.sandbox.allow-private-networks", "true");
    // Provider 0 (mock) is repeated as in application.yaml; 1 replaces the DeepSeek entry in full;
    // 2 points at a port nobody listens on.
    p.put("oryxos.providers[0].name", "mock");
    p.put("oryxos.providers[0].type", "mock");
    p.put("oryxos.providers[0].model", "mock-echo");
    p.put("oryxos.providers[1].name", FAKE_PROVIDER);
    p.put("oryxos.providers[1].type", "openai-compatible");
    p.put("oryxos.providers[1].model", "fake-model");
    p.put("oryxos.providers[1].base-url", fakeModelBaseUrl);
    p.put("oryxos.providers[1].api-key", DUMMY_KEY);
    p.put("oryxos.providers[2].name", UNREACHABLE_PROVIDER);
    p.put("oryxos.providers[2].type", "openai-compatible");
    p.put("oryxos.providers[2].model", "fake-model");
    p.put("oryxos.providers[2].base-url", "http://127.0.0.1:9");
    p.put("oryxos.providers[2].api-key", DUMMY_KEY);
    return p;
  }

  void register(DynamicPropertyRegistry registry, String fakeModelBaseUrl) {
    properties(fakeModelBaseUrl).forEach((key, value) -> registry.add(key, () -> value));
  }

  /** Like {@link #properties(String)} with some values replaced or added (sandbox ceiling, ...). */
  Map<String, String> properties(String fakeModelBaseUrl, Map<String, String> overrides) {
    java.util.LinkedHashMap<String, String> p =
        new java.util.LinkedHashMap<>(properties(fakeModelBaseUrl));
    p.putAll(overrides);
    return p;
  }

  void register(
      DynamicPropertyRegistry registry, String fakeModelBaseUrl, Map<String, String> overrides) {
    properties(fakeModelBaseUrl, overrides).forEach((key, value) -> registry.add(key, () -> value));
  }

  // ---- SQLite helpers used by every end-to-end test -----------------------------------------

  static int count(JdbcTemplate jdbc, String table, String sessionId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE session_id = ?", Integer.class, sessionId);
    return n == null ? 0 : n;
  }

  /** Every row of a table as one comparable string per row, ordered by the given column. */
  static List<String> dump(JdbcTemplate jdbc, String sql) {
    return jdbc.query(
        sql,
        (rs, i) -> {
          StringBuilder row = new StringBuilder();
          int columns = rs.getMetaData().getColumnCount();
          for (int c = 1; c <= columns; c++) {
            row.append(rs.getMetaData().getColumnLabel(c))
                .append('=')
                .append(rs.getString(c))
                .append(';');
          }
          return row.toString();
        });
  }
}
