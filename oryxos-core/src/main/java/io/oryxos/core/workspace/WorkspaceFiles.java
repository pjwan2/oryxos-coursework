package io.oryxos.core.workspace;

import java.util.List;

/**
 * Restricted access to the files of the agents directory. Only the directories of registered
 * AGENT.md agents are reachable, only plain text file types, and never through links or {@code ..}.
 */
public interface WorkspaceFiles {
  /** At most a few hundred entries, parents before children. */
  List<WorkspaceEntry> tree();

  WorkspaceFile read(String path);

  /** Saving an AGENT.md validates and re-registers the agent; the old agent stays if it fails. */
  WorkspaceFile save(String path, String content);
}
