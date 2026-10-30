package io.oryxos.provider.agent;

import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.profile.ProfileValidator;
import io.oryxos.core.schedule.ProfileScheduleSync;
import io.oryxos.provider.ProfileLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Builds agent directories on disk and the loader around them. */
final class AgentFixtures {
  static final Set<String> TOOLS = Set.of("read_file", "write_file", "http_get", "save_memory");

  static final String DAILY =
      """
      ---
      name: daily-reconcile
      description: Reconciles the day's payments
      provider: {name: mock, model: m1, temperature: 0.3}
      tools: [read_file, http_get]
      notify_channels:
        - {type: webhook, name: ops}
      schedules:
        - {id: nightly, cron: "0 0 2 * * *", zone: UTC, message: "Reconcile yesterday"}
      settings:
        max_iterations: 4
      ---
      # Daily reconcile

      Compare the ledger with the bank file and report differences.
      """;

  private AgentFixtures() {}

  static Path write(Path root, String name, String agentMd) {
    try {
      Path dir = root.resolve(name);
      Files.createDirectories(dir);
      Files.writeString(dir.resolve("AGENT.md"), agentMd);
      return dir;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void file(Path dir, String relative, String content) {
    try {
      Path target = dir.resolve(relative);
      Files.createDirectories(target.getParent());
      Files.writeString(target, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static ProfileLoader profiles(Path dir) {
    return new ProfileLoader(dir, Set.of("mock", "deepseek"));
  }

  static AgentLoader loader(Path profilesDir, ProfileValidator... extra) {
    return new AgentLoader(profiles(profilesDir), () -> TOOLS, List.of(extra));
  }

  /** Records what the lifecycle told the scheduler; can be made to fail. */
  static final class RecordingSync implements ProfileScheduleSync {
    final List<String> calls = new ArrayList<>();
    RuntimeException failOnRegister;

    @Override
    public void registerProfile(AgentProfile profile) {
      calls.add("register:" + profile.name());
      if (failOnRegister != null) {
        throw failOnRegister;
      }
    }

    @Override
    public void unregisterProfile(String profileName) {
      calls.add("unregister:" + profileName);
    }
  }
}
