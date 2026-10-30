package io.oryxos.core.profile;

import java.util.Objects;

/** A file of an agent directory: a path relative to that directory, and its text. */
public record AgentFile(String path, String content) {
  public AgentFile {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(content, "content");
  }
}
