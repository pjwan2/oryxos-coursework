package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.cli.OryxCli;
import io.oryxos.core.sandbox.SandboxConfigException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.NestedExceptionUtils;

/** An unsafe sandbox setting must stop the application from starting, with a clear reason. */
class SandboxStartupValidationTest {
  @TempDir Path temp;

  private Throwable rootCauseOfStarting(Path profiles, String... extra) {
    List<String> args = new ArrayList<>();
    args.add("--oryxos.scheduler.enabled=false");
    args.add("--logging.level.root=OFF");
    args.add("--oryxos.profile-directory=" + profiles.toString().replace('\\', '/'));
    args.add(
        "--spring.datasource.url=jdbc:sqlite:"
            + temp.resolve("s.db").toString().replace('\\', '/'));
    args.add("--oryxos.memory.markdown-path=" + temp.resolve("M.md").toString().replace('\\', '/'));
    args.addAll(List.of(extra));
    Exception failure =
        assertThrows(
            Exception.class,
            () ->
                new SpringApplicationBuilder(OryxOsApplication.class)
                    .web(WebApplicationType.NONE)
                    .bannerMode(Banner.Mode.OFF)
                    .run(args.toArray(String[]::new))
                    .close());
    Throwable root = NestedExceptionUtils.getRootCause(failure);
    return root == null ? failure : root;
  }

  private Path emptyProfiles() throws IOException {
    return Files.createDirectories(temp.resolve("profiles"));
  }

  @Test
  void aWildcardOnlyHostInTheOperatorConfigurationStopsStartup() throws IOException {
    Throwable root = rootCauseOfStarting(emptyProfiles(), "--oryxos.sandbox.hosts=*");
    assertInstanceOf(SandboxConfigException.class, root);
    assertTrue(root.getMessage().contains("oryxos.sandbox.hosts[0]"), root.getMessage());
  }

  @Test
  void aWholeDriveOrRootAsAnAllowedDirectoryStopsStartup() throws IOException {
    Throwable root = rootCauseOfStarting(emptyProfiles(), "--oryxos.sandbox.read-roots=/");
    assertInstanceOf(SandboxConfigException.class, root);
    assertTrue(root.getMessage().contains("oryxos.sandbox.read_roots[0]"), root.getMessage());
  }

  @Test
  void aPathStyleCommandInTheOperatorConfigurationStopsStartup() throws IOException {
    Throwable root = rootCauseOfStarting(emptyProfiles(), "--oryxos.sandbox.commands=/bin/sh");
    assertInstanceOf(SandboxConfigException.class, root);
  }

  @Test
  void aNonsenseShellTimeoutStopsStartup() throws IOException {
    Throwable root =
        rootCauseOfStarting(emptyProfiles(), "--oryxos.sandbox.shell-timeout-seconds=0");
    assertInstanceOf(SandboxConfigException.class, root);
    assertTrue(root.getMessage().contains("shell-timeout-seconds"), root.getMessage());
  }

  @Test
  void anUnsafeSandboxSectionInAProfileFileStopsStartupAndNamesTheFile() throws IOException {
    Path profiles = emptyProfiles();
    Files.writeString(
        profiles.resolve("risky.yaml"),
        "name: risky\ndescription: d\nprovider: {name: mock, model: m, temperature: 0.2}\n"
            + "settings:\n  sandbox:\n    commands:\n      - {name: git, args: [\".*\"]}\n");
    Throwable root = rootCauseOfStarting(profiles);
    assertInstanceOf(SandboxConfigException.class, root);
    assertTrue(root.getMessage().startsWith("risky.yaml: "), root.getMessage());
    assertTrue(root.getMessage().contains("accepts any argument"), root.getMessage());
  }

  @Test
  void theCliReportsTheSameFailureAsAnErrorLineWithExitCode1() throws IOException {
    StringWriter err = new StringWriter();
    var runtime =
        new BootCliRuntime(
            Map.of(
                "oryxos.sandbox.hosts",
                "*",
                "oryxos.profile-directory",
                emptyProfiles().toString(),
                "spring.datasource.url",
                "jdbc:sqlite:" + temp.resolve("c.db").toString().replace('\\', '/'),
                "oryxos.memory.markdown-path",
                temp.resolve("C.md").toString()));
    int code =
        OryxCli.create(
                runtime,
                new PrintWriter(new StringWriter(), true),
                new PrintWriter(err, true),
                new ByteArrayInputStream(new byte[0]))
            .execute("chat", "any", "hi");
    assertEquals(1, code);
    assertTrue(err.toString().startsWith("Error: Startup failed: "), err.toString());
    assertTrue(err.toString().contains("oryxos.sandbox.hosts[0]"), err.toString());
    assertEquals(1, err.toString().strip().lines().count(), "one line, no stack trace");
  }

  @Test
  void theDefaultConfigurationStartsAndGrantsNoProfileAnything() throws IOException {
    // No sandbox flags at all: startup must succeed with the safe defaults.
    List<String> args =
        List.of(
            "--oryxos.scheduler.enabled=false",
            "--logging.level.root=OFF",
            "--oryxos.profile-directory=" + emptyProfiles().toString().replace('\\', '/'),
            "--spring.datasource.url=jdbc:sqlite:"
                + temp.resolve("d.db").toString().replace('\\', '/'),
            "--oryxos.memory.markdown-path=" + temp.resolve("D.md").toString().replace('\\', '/'));
    try (var context =
        new SpringApplicationBuilder(OryxOsApplication.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .run(args.toArray(String[]::new))) {
      assertTrue(context.getBeansOfType(io.oryxos.core.sandbox.Sandbox.class).size() == 1);
    }
  }
}
