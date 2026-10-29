package io.oryxos.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.sandbox.Sandbox;
import io.oryxos.core.sandbox.SandboxViolation;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileSandboxTest {
  @TempDir Path temp;
  private Path a;
  private Path b;
  private ProfileSandbox sandbox;
  private AgentProfile profileA;
  private AgentProfile profileB;

  @BeforeEach
  void setUp() throws IOException {
    Path ws = Files.createDirectories(temp.resolve("ws"));
    a = Files.createDirectories(ws.resolve("a"));
    b = Files.createDirectories(ws.resolve("b"));
    SandboxPolicy ceiling =
        new SandboxPolicy(
            List.of(ws),
            List.of(ws),
            Set.of("java", "git"),
            Set.of("api.a.example.com", "*.b.example.org", "127.0.0.1"));
    sandbox = new ProfileSandbox(ceiling, host -> List.of(publicAddress()));
    profileA =
        TestProfiles.with(
            "agent-a",
            Map.of(
                "read_roots", List.of(a.toString()),
                "write_roots", List.of(a.toString()),
                "hosts", List.of("api.a.example.com"),
                "commands", List.of(Map.of("name", "java", "args", List.of("-version")))));
    profileB =
        TestProfiles.with(
            "agent-b",
            Map.of(
                "read_roots", List.of(b.toString()),
                "hosts", List.of("news.b.example.org"),
                "commands", List.of("git")));
  }

  private static InetAddress publicAddress() {
    try {
      return InetAddress.getByName("93.184.216.34");
    } catch (java.net.UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  private Sandbox as(AgentProfile profile) {
    return sandbox.forProfile(profile);
  }

  private static void denies(Runnable action) {
    assertThrows(SandboxViolation.class, action::run);
  }

  @Test
  void eachProfileSeesOnlyItsOwnFiles() {
    assertDoesNotThrow(() -> as(profileA).checkPath(a.resolve("x"), Sandbox.Access.READ));
    assertDoesNotThrow(() -> as(profileB).checkPath(b.resolve("x"), Sandbox.Access.READ));
    denies(() -> as(profileA).checkPath(b.resolve("x"), Sandbox.Access.READ));
    denies(() -> as(profileB).checkPath(a.resolve("x"), Sandbox.Access.READ));
  }

  @Test
  void eachProfileKeepsItsOwnReadWriteSplit() {
    assertDoesNotThrow(() -> as(profileA).checkPath(a.resolve("x"), Sandbox.Access.WRITE));
    denies(
        () -> as(profileB).checkPath(b.resolve("x"), Sandbox.Access.WRITE)); // B declared read only
  }

  @Test
  void eachProfileSeesOnlyItsOwnHosts() {
    assertDoesNotThrow(
        () -> as(profileA).checkHttpTarget(URI.create("https://api.a.example.com/")));
    assertDoesNotThrow(
        () -> as(profileB).checkHttpTarget(URI.create("https://news.b.example.org/")));
    denies(() -> as(profileA).checkHttpTarget(URI.create("https://news.b.example.org/")));
    denies(() -> as(profileB).checkHttpTarget(URI.create("https://api.a.example.com/")));
  }

  @Test
  void eachProfileSeesOnlyItsOwnCommandsAndArgumentRules() {
    assertDoesNotThrow(() -> as(profileA).checkCommand(List.of("java", "-version")));
    denies(() -> as(profileA).checkCommand(List.of("java", "-jar", "x.jar")));
    denies(() -> as(profileA).checkCommand(List.of("git")));
    assertDoesNotThrow(() -> as(profileB).checkCommand(List.of("git")));
    denies(() -> as(profileB).checkCommand(List.of("git", "status"))); // no argument rule = none
    denies(() -> as(profileB).checkCommand(List.of("java", "-version")));
  }

  @Test
  void aProfileThatDeclaresNothingGetsNothingNotTheOtherAgentsPermissionsNorADefault() {
    AgentProfile silent = TestProfiles.with("silent", null);
    denies(() -> as(silent).checkPath(a.resolve("x"), Sandbox.Access.READ));
    denies(() -> as(silent).checkPath(b.resolve("x"), Sandbox.Access.READ));
    denies(() -> as(silent).checkHttpTarget(URI.create("https://api.a.example.com/")));
    denies(() -> as(silent).checkCommand(List.of("git")));
    // And it did not weaken anyone else.
    assertDoesNotThrow(() -> as(profileA).checkPath(a.resolve("x"), Sandbox.Access.READ));
  }

  @Test
  void aProfileCannotGrantItselfMoreThanTheOperatorCeiling() {
    AgentProfile greedy =
        TestProfiles.with(
            "greedy",
            Map.of(
                "read_roots",
                    List.of(
                        a.toString(),
                        temp.toString(),
                        temp.getRoot().resolve("Windows").toString()),
                "hosts",
                    List.of(
                        "api.a.example.com",
                        "evil.com",
                        "*.example.org",
                        "api.b.example.org",
                        "*.b.example.org",
                        "10.0.0.5"),
                "commands", List.of("java", "rm", "bash"),
                "allow_private_networks", true));
    Sandbox s = as(greedy);
    assertDoesNotThrow(() -> s.checkPath(a.resolve("x"), Sandbox.Access.READ));
    denies(() -> s.checkPath(temp.resolve("outside.txt"), Sandbox.Access.READ));
    assertDoesNotThrow(() -> s.checkHttpTarget(URI.create("https://api.a.example.com/")));
    assertDoesNotThrow(() -> s.checkHttpTarget(URI.create("https://api.b.example.org/")));
    assertDoesNotThrow(() -> s.checkHttpTarget(URI.create("https://deep.b.example.org/")));
    denies(() -> s.checkHttpTarget(URI.create("https://evil.com/")));
    denies(() -> s.checkHttpTarget(URI.create("https://other.example.org/")));
    denies(() -> s.checkHttpTarget(URI.create("http://10.0.0.5/")));
    denies(() -> s.checkCommand(List.of("rm")));
    denies(() -> s.checkCommand(List.of("bash")));
    assertDoesNotThrow(() -> s.checkCommand(List.of("java")));
  }

  @Test
  void privateNetworksNeedBothTheProfileAndTheOperator() throws Exception {
    Path ws = a.getParent();
    var privateResolver =
        (java.util.function.Function<String, List<InetAddress>>) h -> List.of(loopback());
    var operatorAllows =
        new ProfileSandbox(
            new SandboxPolicy(List.of(ws), List.of(), Map.of(), Set.of("lan.example.org"), true),
            privateResolver);
    var operatorDenies =
        new ProfileSandbox(
            new SandboxPolicy(List.of(ws), List.of(), Map.of(), Set.of("lan.example.org"), false),
            privateResolver);
    AgentProfile wants =
        TestProfiles.with(
            "wants", Map.of("hosts", List.of("lan.example.org"), "allow_private_networks", true));
    AgentProfile silentAboutIt =
        TestProfiles.with("quiet", Map.of("hosts", List.of("lan.example.org")));

    URI lan = URI.create("http://lan.example.org/");
    assertDoesNotThrow(() -> operatorAllows.forProfile(wants).checkHttpTarget(lan));
    denies(() -> operatorAllows.forProfile(silentAboutIt).checkHttpTarget(lan));
    denies(() -> operatorDenies.forProfile(wants).checkHttpTarget(lan));
  }

  private static InetAddress loopback() {
    return InetAddress.getLoopbackAddress();
  }

  @Test
  void usingTheSandboxWithoutAProfileFailsClosed() {
    denies(() -> sandbox.checkPath(a.resolve("x"), Sandbox.Access.READ));
    denies(() -> sandbox.checkCommand(List.of("java")));
    denies(() -> sandbox.checkUri(URI.create("https://api.a.example.com/")));
    denies(() -> sandbox.checkHttpTarget(URI.create("https://api.a.example.com/")));
  }

  @Test
  void aMalformedDeclarationOnAProfileBuiltElsewhereDeniesEverythingInsteadOfFailingOpen() {
    AgentProfile broken = TestProfiles.with("broken", Map.of("hosts", "not-a-list"));
    denies(() -> as(broken).checkPath(a.resolve("x"), Sandbox.Access.READ));
    AgentProfile typo = TestProfiles.with("typo", Map.of("host", List.of("api.a.example.com")));
    denies(() -> as(typo).checkHttpTarget(URI.create("https://api.a.example.com/")));
  }

  @Test
  void theScopedSandboxIsStablePerProfileAndDistinctBetweenProfiles() {
    assertSame(as(profileA), as(profileA));
    assertTrue(as(profileA) != as(profileB));
  }

  @Test
  void afterAFailureForOneProfileTheNextRequestStartsClean() {
    // Same thread, one after another: nothing remembered from the denied call.
    denies(() -> as(profileA).checkPath(b.resolve("x"), Sandbox.Access.READ));
    assertDoesNotThrow(() -> as(profileB).checkPath(b.resolve("x"), Sandbox.Access.READ));
    denies(() -> as(profileB).checkPath(a.resolve("x"), Sandbox.Access.READ));
    assertDoesNotThrow(() -> as(profileA).checkPath(a.resolve("x"), Sandbox.Access.READ));
  }

  @Test
  void interleavedConcurrentRequestsNeverSeeEachOthersPermissions() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<String>> results = new ArrayList<>();
      for (int i = 0; i < 400; i++) {
        final boolean useA = i % 2 == 0;
        Callable<String> task =
            () -> {
              AgentProfile me = useA ? profileA : profileB;
              Path mine = (useA ? a : b).resolve("f");
              Path theirs = (useA ? b : a).resolve("f");
              as(me).checkPath(mine, Sandbox.Access.READ);
              try {
                as(me).checkPath(theirs, Sandbox.Access.READ);
                return "LEAK: " + me.name() + " reached the other agent's directory";
              } catch (SandboxViolation expected) {
                return "ok";
              }
            };
        results.add(pool.submit(task));
      }
      for (Future<String> result : results) {
        assertEquals("ok", result.get());
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
