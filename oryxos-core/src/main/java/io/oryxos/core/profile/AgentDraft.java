package io.oryxos.core.profile;

import java.util.List;

/**
 * Files proposed by the model for an agent. Nothing has been saved: the draft is for a person to
 * read, edit and then confirm. {@code valid} says whether the AGENT.md in it passes the same
 * validation as a saved one; {@code problems} explains what is wrong if not.
 */
public record AgentDraft(List<AgentFile> files, boolean valid, List<String> problems) {
  public AgentDraft {
    files = List.copyOf(files);
    problems = List.copyOf(problems);
  }

  @Override
  public List<AgentFile> files() {
    return List.copyOf(files);
  }

  @Override
  public List<String> problems() {
    return List.copyOf(problems);
  }
}
