package io.oryxos.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.oryxos.core.sandbox.Sandbox;
import io.oryxos.core.sandbox.SandboxViolation;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AllowListSandboxTest {
  @TempDir Path temp;

  private AllowListSandbox sandbox(Path read, Path write) {
    return new AllowListSandbox(
        new SandboxPolicy(
            read == null ? List.of() : List.of(read),
            write == null ? List.of() : List.of(write),
            Set.of("git"),
            Set.of("api.example.com", "*.trusted.org")));
  }

  @Test
  void defaultPolicyDeniesEverything() {
    var deny = new AllowListSandbox(SandboxPolicy.denyAll());
    assertThrows(SandboxViolation.class, () -> deny.checkPath(temp, Sandbox.Access.READ));
    assertThrows(SandboxViolation.class, () -> deny.checkCommand(List.of("git")));
    assertThrows(
        SandboxViolation.class, () -> deny.checkUri(URI.create("https://api.example.com/x")));
  }

  @Test
  void pathInsideRootIsAllowedAndRelativeEscapesAreDenied() throws IOException {
    Path root = Files.createDirectory(temp.resolve("root"));
    Files.createDirectory(temp.resolve("secret"));
    var sb = sandbox(root, null);
    assertEquals(
        root.toRealPath().resolve("a.txt"),
        sb.checkPath(root.resolve("a.txt"), Sandbox.Access.READ));
    Path escape = root.resolve("..").resolve("secret").resolve("x");
    assertThrows(SandboxViolation.class, () -> sb.checkPath(escape, Sandbox.Access.READ));
  }

  @Test
  void readRootDoesNotGrantWrite() throws IOException {
    Path root = Files.createDirectory(temp.resolve("root"));
    var sb = sandbox(root, null);
    Path target = root.resolve("a.txt");
    assertThrows(SandboxViolation.class, () -> sb.checkPath(target, Sandbox.Access.WRITE));
  }

  @Test
  void siblingWithSamePrefixIsNotInsideRoot() throws IOException {
    Path root = Files.createDirectory(temp.resolve("data"));
    Path sibling = Files.createDirectory(temp.resolve("data-private"));
    var sb = sandbox(root, null);
    Path target = sibling.resolve("x");
    assertThrows(SandboxViolation.class, () -> sb.checkPath(target, Sandbox.Access.READ));
  }

  @Test
  void commandMustBeABareAllowlistedName() {
    var sb = sandbox(null, null);
    sb.checkCommand(List.of("git", "status"));
    assertThrows(SandboxViolation.class, () -> sb.checkCommand(List.of("rm", "-rf", "/")));
    assertThrows(SandboxViolation.class, () -> sb.checkCommand(List.of("./git")));
    assertThrows(SandboxViolation.class, () -> sb.checkCommand(List.of("C:\\x\\git")));
    assertThrows(SandboxViolation.class, () -> sb.checkCommand(List.of()));
  }

  @Test
  void uriNeedsAllowedHostAndHttpScheme() {
    var sb = sandbox(null, null);
    sb.checkUri(URI.create("https://api.example.com/v1"));
    sb.checkUri(URI.create("http://news.trusted.org/x"));
    assertThrows(SandboxViolation.class, () -> sb.checkUri(URI.create("https://evil.com")));
    assertThrows(SandboxViolation.class, () -> sb.checkUri(URI.create("file:///etc/passwd")));
    assertThrows(
        SandboxViolation.class, () -> sb.checkUri(URI.create("https://api.example.com@evil.com/")));
    assertThrows(SandboxViolation.class, () -> sb.checkUri(URI.create("https://nottrusted.org/")));
  }
}
