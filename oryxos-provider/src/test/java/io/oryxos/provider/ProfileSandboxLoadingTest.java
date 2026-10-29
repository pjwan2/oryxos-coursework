package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.sandbox.SandboxConfigException;
import io.oryxos.core.sandbox.SandboxSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A Profile's sandbox section is validated when the file is loaded, not when it is first used. */
class ProfileSandboxLoadingTest {
  @TempDir Path temp;

  private static String profile(String name, String settings) {
    return "name: "
        + name
        + "\ndescription: t\nprovider: {name: mock, model: m, temperature: 0.2}\n"
        + "tools: [file_read]\n"
        + settings;
  }

  private ProfileLoader loader() {
    return new ProfileLoader(temp, Set.of("mock"));
  }

  @Test
  void aValidSandboxSectionSurvivesLoadingIntoSettings() throws IOException {
    Files.writeString(
        temp.resolve("a.yaml"),
        profile(
            "agent-a",
            """
            settings:
              max_iterations: 5
              sandbox:
                read_roots: [".oryxos/workspace/a"]
                hosts: ["api.example.org"]
                commands:
                  - name: git
                    args: ["status", "log"]
                    max_args: 3
                  - date
            """));
    AgentProfile loaded = loader().loadAll().get("agent-a");
    SandboxSpec spec = SandboxSpec.parse(loaded.settings().get("sandbox"), "sandbox");
    assertEquals(java.util.List.of(".oryxos/workspace/a"), spec.readRoots());
    assertEquals(java.util.List.of("api.example.org"), spec.hosts());
    assertEquals(2, spec.commands().size());
    assertEquals(5, loaded.settings().get("max_iterations"));
  }

  @Test
  void aProfileWithoutASandboxSectionLoadsAndIsDenyAll() throws IOException {
    Files.writeString(temp.resolve("quiet.yaml"), profile("quiet", ""));
    AgentProfile loaded = loader().loadAll().get("quiet");
    assertEquals(
        SandboxSpec.denyAll(), SandboxSpec.parse(loaded.settings().get("sandbox"), "sandbox"));
  }

  @Test
  void anInvalidSandboxSectionStopsLoadingInsteadOfBeingSkippedQuietly() throws IOException {
    Files.writeString(temp.resolve("good.yaml"), profile("good", ""));
    Files.writeString(
        temp.resolve("evil.yaml"), profile("evil", "settings:\n  sandbox:\n    hosts: [\"*\"]\n"));
    SandboxConfigException error =
        assertThrows(SandboxConfigException.class, () -> loader().loadAll());
    assertTrue(error.getMessage().startsWith("evil.yaml: "), error.getMessage());
    assertTrue(error.getMessage().contains("settings.sandbox.hosts[0]"), error.getMessage());
  }

  @Test
  void typosAndWrongTypesInTheSectionAreRejected() throws IOException {
    for (String bad :
        new String[] {
          "settings:\n  sandbox:\n    host: [a.example.org]\n",
          "settings:\n  sandbox: everything\n",
          "settings:\n  sandbox:\n    read_roots: /\n",
          "settings:\n  sandbox:\n    commands: [\"/bin/sh\"]\n",
          "settings:\n  sandbox:\n    commands:\n      - {name: git, args: [\".*\"]}\n",
          "settings:\n  sandbox:\n    allow_private_networks: yes please\n"
        }) {
      Path file = temp.resolve("p.yaml");
      Files.writeString(file, profile("p", bad));
      assertThrows(SandboxConfigException.class, () -> loader().loadAll(), bad);
      Files.delete(file);
    }
  }

  @Test
  void otherKindsOfInvalidProfilesAreStillSkippedAsBefore() throws IOException {
    Files.writeString(temp.resolve("ok.yaml"), profile("ok", ""));
    Files.writeString(temp.resolve("bad.yaml"), "name: x\nprovider: {name: missing, model: m}\n");
    Map<String, AgentProfile> loaded = loader().loadAll();
    assertEquals(Set.of("ok"), loaded.keySet());
  }
}
