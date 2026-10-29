package io.oryxos.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.oryxos.core.sandbox.Sandbox;
import io.oryxos.core.sandbox.SandboxViolation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * File access decisions. Windows-only cases are guarded by assumeTrue; on another OS they are
 * reported as skipped, never as passed.
 */
class PathSecurityTest {
  private static final boolean WINDOWS =
      System.getProperty("os.name").toLowerCase().contains("win");
  @TempDir Path temp;
  private Path root;
  private Path secret;
  private AllowListSandbox sandbox;

  @BeforeEach
  void setUp() throws IOException {
    root = Files.createDirectories(temp.resolve("allowed"));
    secret = Files.createDirectories(temp.resolve("secret"));
    Files.writeString(secret.resolve("key.txt"), "top secret");
    Files.writeString(root.resolve("ok.txt"), "fine");
    sandbox =
        new AllowListSandbox(new SandboxPolicy(List.of(root), List.of(root), Set.of(), Set.of()));
  }

  /**
   * A spelling counts as refused when EITHER the platform cannot even parse it (Windows rejects ':'
   * and trailing spaces in Path.of) OR the sandbox denies it.
   */
  private void deniedSpelling(String spelling) {
    Path parsed;
    try {
      parsed = Path.of(spelling);
    } catch (java.nio.file.InvalidPathException platformRefuses) {
      return;
    }
    denied(parsed);
  }

  private void denied(Path path) {
    assertThrows(
        SandboxViolation.class,
        () -> sandbox.checkPath(path, Sandbox.Access.READ),
        path.toString());
  }

  @Test
  void ordinaryPathsInsideTheRootAreAllowed() throws IOException {
    assertEquals(
        root.toRealPath().resolve("ok.txt"),
        sandbox.checkPath(root.resolve("ok.txt"), Sandbox.Access.READ));
    sandbox.checkPath(root.resolve("sub/dir/not-yet-created.txt"), Sandbox.Access.WRITE);
    sandbox.checkPath(root.resolve("./a/./b.txt"), Sandbox.Access.READ);
  }

  @Test
  void dotDotTraversalIsDeniedHoweverItIsSpelled() {
    denied(root.resolve("..").resolve("secret").resolve("key.txt"));
    denied(root.resolve("sub/../../secret/key.txt"));
    denied(root.resolve("a/b/../../../secret/key.txt"));
    denied(root.resolve(".."));
  }

  @Test
  void anAbsolutePathOutsideTheRootIsDenied() {
    denied(secret.resolve("key.txt"));
    denied(temp);
    denied(temp.getRoot());
  }

  @Test
  void aSiblingWithTheSameNamePrefixIsNotInsideTheRoot() throws IOException {
    Path sibling = Files.createDirectories(temp.resolve("allowed-private"));
    denied(sibling.resolve("x.txt"));
  }

  @Test
  void relativePathsAreResolvedAgainstTheWorkingDirectoryNotTrusted() {
    denied(Path.of("definitely-not-in-the-sandbox.txt"));
    denied(Path.of("..", "..", "etc", "passwd"));
  }

  @Test
  void aSymlinkInsideTheRootPointingOutsideCannotBeFollowed() throws IOException {
    Path link = root.resolve("escape");
    try {
      Files.createSymbolicLink(link, secret);
    } catch (UnsupportedOperationException | IOException | SecurityException e) {
      assumeTrue(false, "symbolic links not permitted here: " + e);
    }
    denied(link.resolve("key.txt"));
    denied(link);
  }

  @Test
  void aSymlinkedFileInsideTheRootPointingOutsideIsDenied() throws IOException {
    Path link = root.resolve("shortcut.txt");
    try {
      Files.createSymbolicLink(link, secret.resolve("key.txt"));
    } catch (UnsupportedOperationException | IOException | SecurityException e) {
      assumeTrue(false, "symbolic links not permitted here: " + e);
    }
    denied(link);
  }

  @Test
  void aSymlinkThatStaysInsideTheRootIsAllowed() throws IOException {
    Path inner = Files.createDirectories(root.resolve("real"));
    Path link = root.resolve("alias");
    try {
      Files.createSymbolicLink(link, inner);
    } catch (UnsupportedOperationException | IOException | SecurityException e) {
      assumeTrue(false, "symbolic links not permitted here: " + e);
    }
    sandbox.checkPath(link.resolve("f.txt"), Sandbox.Access.WRITE);
  }

  @Test
  void lexicalNormalisationCannotHideALinkTraversal() throws IOException {
    // root/escape/../key.txt looks like root/key.txt after lexical normalisation, but the OS would
    // resolve "escape" first. The path that is checked is the path that is opened.
    Path link = root.resolve("escape");
    try {
      Files.createSymbolicLink(link, secret.resolve("deeper").getParent());
    } catch (UnsupportedOperationException | IOException | SecurityException e) {
      assumeTrue(false, "symbolic links not permitted here: " + e);
    }
    Path checked =
        sandbox.checkPath(
            root.resolve("escape").resolve("..").resolve("ok.txt"), Sandbox.Access.READ);
    assertEquals(root.toRealPath().resolve("ok.txt"), checked);
  }

  @Test
  void aWindowsDirectoryJunctionPointingOutsideIsDenied() throws Exception {
    assumeTrue(WINDOWS, "directory junctions exist only on Windows");
    Path junction = root.resolve("junction");
    Process p =
        new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), secret.toString())
            .redirectErrorStream(true)
            .start();
    p.getInputStream().readAllBytes();
    assumeTrue(p.waitFor() == 0, "mklink /J failed in this environment");
    denied(junction.resolve("key.txt"));
    denied(junction);
  }

  @Test
  void uncAndDevicePathsAreRefusedBecauseTheyCannotBeVerified() {
    assumeTrue(WINDOWS, "UNC syntax is Windows-specific");
    denied(Path.of("\\\\server\\share\\file.txt"));
    deniedSpelling("\\\\?\\C:\\Windows\\win.ini");
    deniedSpelling("\\\\.\\C:\\Windows\\win.ini");
    denied(Path.of("\\\\localhost\\C$\\Windows\\win.ini"));
  }

  @Test
  void driveRelativePathsAreRefused() {
    assumeTrue(WINDOWS, "drive-relative paths exist only on Windows");
    denied(Path.of("C:secret.txt"));
    denied(Path.of("D:..\\x"));
  }

  @Test
  void alternateDataStreamsAndColonsAreRefused() {
    deniedSpelling(root.resolve("ok.txt") + ":hidden");
    deniedSpelling(root.resolve("ok.txt") + "::$DATA");
    deniedSpelling(root + "/dir:stream/x");
  }

  @Test
  void windowsReservedDeviceNamesAreRefusedOnEveryPlatform() {
    for (String name : List.of("NUL", "con", "Aux.txt", "COM1", "lpt9.log", "PRN", "CONIN$")) {
      denied(root.resolve(name));
    }
    sandbox.checkPath(root.resolve("console.txt"), Sandbox.Access.READ); // not a reserved name
    sandbox.checkPath(root.resolve("nullable.txt"), Sandbox.Access.READ);
  }

  @Test
  void trailingDotsAndSpacesAndWindowsMetacharactersAreRefused() {
    deniedSpelling(root.resolve("ok.txt") + ".");
    deniedSpelling(root.resolve("ok.txt") + " ");
    deniedSpelling(root + "/dir./x");
    deniedSpelling(root + "/a|b");
    deniedSpelling(root + "/a?b");
    deniedSpelling(root + "/a*b");
    deniedSpelling(root + "/a<b");
  }

  @Test
  void pathCaseDifferencesAreNotAWayInOrOutOnWindows() {
    assumeTrue(WINDOWS, "case-insensitive file system semantics are Windows-specific");
    String shouted = root.toString().toUpperCase();
    sandbox.checkPath(Path.of(shouted, "ok.txt"), Sandbox.Access.READ);
    denied(Path.of(secret.toString().toUpperCase(), "key.txt"));
  }

  @Test
  void writeRootsAreSeparateFromReadRoots() {
    var readOnly =
        new AllowListSandbox(new SandboxPolicy(List.of(root), List.of(), Set.of(), Set.of()));
    readOnly.checkPath(root.resolve("ok.txt"), Sandbox.Access.READ);
    assertThrows(
        SandboxViolation.class,
        () -> readOnly.checkPath(root.resolve("ok.txt"), Sandbox.Access.WRITE));
  }

  @Test
  void anEmptyPolicyDeniesEverything() {
    var none = new AllowListSandbox(SandboxPolicy.denyAll());
    assertThrows(
        SandboxViolation.class, () -> none.checkPath(root.resolve("ok.txt"), Sandbox.Access.READ));
  }
}
