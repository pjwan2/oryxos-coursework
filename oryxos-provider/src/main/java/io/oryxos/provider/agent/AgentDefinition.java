package io.oryxos.provider.agent;

import io.oryxos.core.profile.AgentProfile;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * An agent read from {@code <agents>/<name>/AGENT.md}: the derived Profile, the Markdown
 * instructions (also the Profile's system prompt) and the names of the resource files that sit
 * beside it. Resource names are listed, never their content.
 */
public record AgentDefinition(
    String name,
    Path directory,
    AgentProfile profile,
    String instructions,
    List<String> resources,
    String contentHash) {
  public AgentDefinition {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(directory, "directory");
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(instructions, "instructions");
    Objects.requireNonNull(contentHash, "contentHash");
    resources = List.copyOf(resources);
  }

  @Override
  public List<String> resources() {
    return List.copyOf(resources);
  }
}
