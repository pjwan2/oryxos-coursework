package io.oryxos.core.profile;

import java.util.List;
import java.util.Map;

/**
 * The one way agents are created, changed and retired at run time, whether the request came from
 * the REST API, the console or a file that appeared in the agents directory. Every method either
 * completes or leaves the files, the registry and the scheduler as they were.
 */
public interface AgentManagement {
  /** A new agent from a fixed template (no model involved); runs at once with the default model. */
  AgentView create(String name, String description);

  AgentView get(String name);

  /** Every registered agent, legacy ones included, ordered by name. */
  List<AgentView> list();

  /**
   * Replaces the AGENT.md of an agent. The text is validated first; if it is invalid, or the
   * scheduler refuses it, the previous file and the running agent are kept.
   */
  AgentView update(String name, String agentMd);

  /**
   * Stops the agent and moves its directory (memory included) to the archive. Nothing is deleted.
   */
  ArchiveResult archive(String name);

  /** Directory name to the reason it is not (or no longer cleanly) registered. */
  Map<String, String> problems();

  /**
   * Writes several files of one agent together (a confirmed draft). All paths and the resulting
   * AGENT.md are checked before anything is written; a failure restores every file.
   */
  AgentView saveFiles(String name, List<AgentFile> files);
}
