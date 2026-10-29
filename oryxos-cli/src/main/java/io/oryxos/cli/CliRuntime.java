package io.oryxos.cli;

import java.util.List;

/**
 * What the commands need from the application. Light methods must not start Spring; heavy methods
 * (chat, serve) may start it lazily. The boot module provides the real implementation.
 */
public interface CliRuntime {
  record ProfileSummary(String name, String provider, String model, List<String> tools) {
    public ProfileSummary {
      tools = List.copyOf(tools);
    }

    @Override
    public List<String> tools() {
      return List.copyOf(tools);
    }
  }

  record ToolSummary(String name, String description) {}

  record StatusReport(
      String version,
      String profileDirectory,
      int profileCount,
      String databasePath,
      boolean databaseExists) {}

  /** Light. */
  List<ProfileSummary> listProfiles();

  /** Light: built-in tools only (MCP tools exist only inside a running application). */
  List<ToolSummary> listTools();

  /** Light. */
  StatusReport status();

  /**
   * Heavy: one turn through AgentService (ReAct, tools, SQLite session and audit).
   *
   * @throws IllegalArgumentException for an unknown profile or invalid input
   */
  String chat(String userId, String profileName, String message);

  /** Heavy: starts the web server and blocks until it stops. */
  void serve(List<String> springArguments);

  /** Releases anything chat() started. */
  void close();
}
