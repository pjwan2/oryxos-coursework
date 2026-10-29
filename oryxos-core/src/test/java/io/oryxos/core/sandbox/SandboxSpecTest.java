package io.oryxos.core.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SandboxSpecTest {
  private static SandboxSpec parse(Object raw) {
    return SandboxSpec.parse(raw, "sandbox");
  }

  private static String rejected(Object raw) {
    return assertThrows(SandboxConfigException.class, () -> parse(raw)).getMessage();
  }

  @Test
  void nothingDeclaredMeansDenyAll() {
    SandboxSpec spec = parse(null);
    assertTrue(spec.readRoots().isEmpty() && spec.writeRoots().isEmpty());
    assertTrue(spec.commands().isEmpty() && spec.hosts().isEmpty());
    assertFalse(spec.allowPrivateNetworks());
    assertEquals(spec, parse(Map.of()));
  }

  @Test
  void aCompleteValidDeclarationIsAccepted() {
    SandboxSpec spec =
        parse(
            Map.of(
                "read_roots", List.of(".oryxos/workspace/a"),
                "write_roots", List.of(".oryxos/workspace/a/out"),
                "hosts",
                    List.of("API.Open-Meteo.com", "*.example.org", "203.0.113.7", "2001:db8::1"),
                "allow_private_networks", false,
                "commands",
                    List.of(
                        "date",
                        Map.of(
                            "name",
                            "git",
                            "args",
                            List.of("status", "-n", "[0-9]{1,3}"),
                            "max_args",
                            4))));
    assertEquals("api.open-meteo.com", spec.hosts().getFirst(), "hosts are lower-cased");
    assertEquals(4, spec.hosts().size());
    assertEquals(0, spec.commands().getFirst().args().size(), "bare program name = no arguments");
    assertEquals(4, spec.commands().get(1).maxArgs());
  }

  @Test
  void unknownKeysAreRejectedBecauseASilentTypoWouldHideAPolicy() {
    assertTrue(rejected(Map.of("host", List.of("a.example.org"))).contains("unknown key 'host'"));
    assertTrue(rejected(Map.of("hosts", List.of(), "extra", 1)).contains("unknown key"));
  }

  @Test
  void wrongTypesAreRejected() {
    rejected("everything");
    rejected(Map.of("hosts", "a.example.org"));
    rejected(Map.of("read_roots", List.of(5)));
    rejected(Map.of("allow_private_networks", "yes"));
    rejected(Map.of("commands", List.of(42)));
    rejected(Map.of("hosts", List.of(" ")));
  }

  @Test
  void aWholeFilesystemRootOrUncPathIsNotAcceptableAsARoot() {
    rejected(Map.of("read_roots", List.of("/")));
    rejected(Map.of("write_roots", List.of("\\\\server\\share\\dir")));
    rejected(Map.of("read_roots", List.of("//server/share")));
    rejected(Map.of("read_roots", List.of("a\0b")));
  }

  @Test
  void overBroadOrMalformedHostsAreRejected() {
    for (String bad :
        List.of(
            "*",
            "*.",
            "*.com",
            "foo*.example.org",
            "exa mple.org",
            "-bad.example.org",
            "a..b.org",
            "http://x.org",
            "x.org/path",
            "user@x.org")) {
      rejected(Map.of("hosts", List.of(bad)));
    }
  }

  @Test
  void commandsMustBeBareProgramNamesWithSpecificArgumentPatterns() {
    rejected(Map.of("commands", List.of("/usr/bin/git")));
    rejected(Map.of("commands", List.of("..\\git.exe")));
    rejected(Map.of("commands", List.of("git status")));
    rejected(Map.of("commands", List.of("git;rm")));
    rejected(Map.of("commands", List.of(Map.of("name", "git", "args", List.of(".*")))));
    rejected(Map.of("commands", List.of(Map.of("name", "git", "args", List.of("(unclosed")))));
    rejected(Map.of("commands", List.of(Map.of("name", "git", "max_args", 1000))));
    rejected(Map.of("commands", List.of(Map.of("name", "git", "arguments", List.of("x")))));
  }

  @Test
  void tooManyEntriesAreRejected() {
    List<String> many =
        java.util.stream.IntStream.range(0, 101).mapToObj(i -> "h" + i + ".example.org").toList();
    assertTrue(rejected(Map.of("hosts", many)).contains("more than"));
  }

  @Test
  void errorsNameTheOffendingPlaceButNotTheValueOfSecrets() {
    String message = rejected(Map.of("hosts", List.of("good.example.org", "bad host")));
    assertTrue(message.startsWith("sandbox.hosts[1]"), message);
  }
}
