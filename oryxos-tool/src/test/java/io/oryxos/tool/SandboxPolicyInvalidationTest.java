package io.oryxos.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.ProfileNotFoundException;
import io.oryxos.core.agent.ToolAction;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.sandbox.Sandbox;
import io.oryxos.core.tool.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tightening a permission must take effect on the very next tool call. The dangerous direction is
 * the one these tests pin down: an agent that was allowed a directory and is then denied it.
 */
class SandboxPolicyInvalidationTest {
  @TempDir Path temp;

  private Path allowed;
  private Path secret;
  private ProfileSandbox sandbox;
  private final AtomicReference<AgentProfile> registry = new AtomicReference<>();

  @BeforeEach
  void setUp() throws IOException {
    Path ws = Files.createDirectories(temp.resolve("ws"));
    allowed = Files.createDirectories(ws.resolve("allowed"));
    secret = Files.createDirectories(ws.resolve("secret"));
    Files.writeString(allowed.resolve("a.txt"), "ALLOWED-CONTENT");
    Files.writeString(secret.resolve("s.txt"), "SECRET-CONTENT");
    sandbox =
        new ProfileSandbox(
            new SandboxPolicy(List.of(ws), List.of(ws), java.util.Set.of(), java.util.Set.of()));
  }

  private AgentProfile reading(String name, Path... roots) {
    return TestProfiles.with(
        name,
        List.of("file_read"),
        Map.of("read_roots", java.util.Arrays.stream(roots).map(Path::toString).toList()),
        List.of());
  }

  private ToolOutput read(RegistryToolExecutor executor, AgentProfile asSeenByTheTurn, Path file) {
    return executor.execute(
        "api:u:" + asSeenByTheTurn.name(),
        asSeenByTheTurn,
        new ToolAction(
            "c1", "file_read", "{\"path\":\"" + file.toString().replace("\\", "\\\\") + "\"}"));
  }

  private RegistryToolExecutor executor() {
    return new RegistryToolExecutor(
        new ToolRegistry(List.of(new FileReadTool(sandbox))),
        name -> {
          AgentProfile current = registry.get();
          if (current == null || !current.name().equals(name)) {
            throw new ProfileNotFoundException(name);
          }
          return current;
        });
  }

  @Test
  void invalidateForgetsTheCachedPolicyOfThatAgentOnly() {
    AgentProfile first = reading("one", allowed);
    AgentProfile other = reading("other", allowed);
    Sandbox cachedFirst = sandbox.forProfile(first);
    Sandbox cachedOther = sandbox.forProfile(other);
    assertSame(cachedFirst, sandbox.forProfile(first), "the policy is cached");

    sandbox.invalidate("one");

    assertNotSame(cachedFirst, sandbox.forProfile(first), "rebuilt after invalidation");
    assertSame(cachedOther, sandbox.forProfile(other), "other agents keep their cache");
  }

  @Test
  void invalidatingAnUnknownAgentIsHarmless() {
    assertDoesNotThrow(() -> sandbox.invalidate("nobody"));
  }

  @Test
  void aDirectoryThatWasAllowedIsDeniedOnTheNextCallAfterThePermissionIsRemoved() {
    AgentProfile loose = reading("worker", allowed, secret);
    registry.set(loose);
    RegistryToolExecutor executor = executor();
    ToolOutput before = read(executor, loose, secret.resolve("s.txt"));
    assertTrue(before.success(), "allowed while the profile grants it");
    assertEquals("SECRET-CONTENT", before.content());

    AgentProfile tight = reading("worker", allowed);
    registry.set(tight);
    sandbox.invalidate("worker");

    ToolOutput after = read(executor, tight, secret.resolve("s.txt"));
    assertTrue(after.isDenied(), "denied right after the change: " + after);
    assertTrue(read(executor, tight, allowed.resolve("a.txt")).success(), "the rest still works");
  }

  @Test
  void aTurnThatStartedBeforeTheChangeIsStillJudgedByTheNewDefinition() {
    AgentProfile loose = reading("worker", allowed, secret);
    registry.set(loose);
    RegistryToolExecutor executor = executor();
    assertTrue(read(executor, loose, secret.resolve("s.txt")).success());

    registry.set(reading("worker", allowed));
    sandbox.invalidate("worker");

    // The turn still holds the OLD snapshot; the executor must look the agent up again.
    ToolOutput stale = read(executor, loose, secret.resolve("s.txt"));
    assertTrue(stale.isDenied(), "the old snapshot must not grant what was withdrawn: " + stale);
  }

  @Test
  void aToolThatWasRemovedFromTheAgentIsRefusedEvenWithTheOldSnapshot() {
    AgentProfile loose = reading("worker", allowed);
    registry.set(loose);
    RegistryToolExecutor executor = executor();
    assertTrue(read(executor, loose, allowed.resolve("a.txt")).success());

    registry.set(
        TestProfiles.with(
            "worker", List.of(), Map.of("read_roots", List.of(allowed.toString())), List.of()));

    assertThrows(SecurityException.class, () -> read(executor, loose, allowed.resolve("a.txt")));
  }

  @Test
  void anAgentThatNoLongerExistsCannotRunToolsAtAll() {
    AgentProfile gone = reading("worker", allowed);
    registry.set(gone);
    RegistryToolExecutor executor = executor();
    registry.set(null);

    assertThrows(
        ProfileNotFoundException.class, () -> read(executor, gone, allowed.resolve("a.txt")));
  }

  @Test
  void withoutALookupTheExecutorKeepsTheSnapshotBehaviourItAlwaysHad() {
    RegistryToolExecutor snapshotOnly =
        new RegistryToolExecutor(new ToolRegistry(List.of(new FileReadTool(sandbox))));
    AgentProfile loose = reading("worker", allowed, secret);

    assertTrue(read(snapshotOnly, loose, secret.resolve("s.txt")).success());
  }
}
