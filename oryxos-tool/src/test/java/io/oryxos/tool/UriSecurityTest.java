package io.oryxos.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.sandbox.Sandbox;
import io.oryxos.core.sandbox.SandboxViolation;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * HTTP target decisions. DNS is a fixed table, so these tests never touch the network. They check
 * the policy; they do not claim to stop DNS rebinding (see docs).
 */
class UriSecurityTest {
  private static InetAddress ip(String literal) {
    try {
      return InetAddress.getByName(literal);
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final Map<String, List<InetAddress>> DNS =
      Map.ofEntries(
          Map.entry("api.example.com", List.of(ip("93.184.216.34"))),
          Map.entry("news.trusted.org", List.of(ip("8.8.8.8"))),
          Map.entry("a.b.trusted.org", List.of(ip("8.8.4.4"))),
          Map.entry("rebind.example.com", List.of(ip("127.0.0.1"))),
          Map.entry("internal.example.com", List.of(ip("10.1.2.3"))),
          Map.entry("meta.example.com", List.of(ip("169.254.169.254"))),
          Map.entry("mixed.example.com", List.of(ip("93.184.216.34"), ip("192.168.0.9"))),
          Map.entry("v6private.example.com", List.of(ip("fd12:3456::1"))),
          Map.entry("v6meta.example.com", List.of(ip("fd00:ec2::254"))),
          Map.entry("cgnat.example.com", List.of(ip("100.64.1.1"))),
          Map.entry("alibaba.example.com", List.of(ip("100.100.100.200"))));

  private static AllowListSandbox sandbox(boolean allowPrivate, String... hosts) {
    return new AllowListSandbox(
        new SandboxPolicy(List.of(), List.of(), java.util.Map.of(), Set.of(hosts), allowPrivate),
        host -> DNS.getOrDefault(host, List.of()));
  }

  private static void denied(Sandbox sandbox, String url) {
    assertThrows(SandboxViolation.class, () -> sandbox.checkHttpTarget(URI.create(url)), url);
  }

  private static void allowed(Sandbox sandbox, String url) {
    assertDoesNotThrow(() -> sandbox.checkHttpTarget(URI.create(url)), url);
  }

  @Test
  void anAllowlistedHostIsAllowedRegardlessOfCaseTrailingDotPortAndPath() {
    var sb = sandbox(false, "api.example.com");
    allowed(sb, "https://api.example.com/v1/x?y=1#frag");
    allowed(sb, "HTTPS://API.EXAMPLE.COM/v1");
    allowed(sb, "http://Api.Example.Com:8080/");
    allowed(sb, "https://api.example.com./v1");
  }

  @Test
  void theDecisionUsesTheParsedHostNotASubstringOfTheUrl() {
    var sb = sandbox(false, "api.example.com");
    denied(sb, "https://evil.com/api.example.com");
    denied(sb, "https://evil.com/?next=https://api.example.com");
    denied(sb, "https://evil.com#api.example.com");
    denied(sb, "https://api.example.com.evil.com/");
    denied(sb, "https://notapi.example.com/");
    denied(sb, "https://xapi.example.com/");
    denied(sb, "https://api-example.com/");
  }

  @Test
  void userInfoTricksAreRefused() {
    var sb = sandbox(false, "api.example.com");
    denied(sb, "https://api.example.com@evil.com/");
    denied(sb, "https://api.example.com:password@evil.com/");
    denied(sb, "https://evil.com@api.example.com/");
    denied(sb, "https://user:pw@api.example.com/");
  }

  @Test
  void wildcardsCoverSubdomainsOnlyNeverTheBareDomainOrLookalikes() {
    var sb = sandbox(false, "*.trusted.org");
    allowed(sb, "https://news.trusted.org/");
    allowed(sb, "https://a.b.trusted.org/");
    denied(sb, "https://trusted.org/");
    denied(sb, "https://eviltrusted.org/");
    denied(sb, "https://trusted.org.evil.com/");
  }

  @Test
  void onlyHttpAndHttpsSchemesPass() {
    var sb = sandbox(false, "api.example.com");
    for (String url :
        List.of(
            "ftp://api.example.com/x",
            "file:///etc/passwd",
            "gopher://api.example.com/",
            "jar:https://api.example.com!/x",
            "javascript:alert(1)",
            "data:text/plain,hi",
            "//api.example.com/x",
            "api.example.com/x")) {
      denied(sb, url);
    }
  }

  @Test
  void ipLiteralsAreRefusedUnlessThatExactAddressWasApproved() {
    var sb = sandbox(false, "api.example.com", "203.0.113.7");
    allowed(sb, "http://203.0.113.7/x");
    denied(sb, "http://203.0.113.8/x");
    denied(sb, "http://8.8.8.8/");
    denied(sb, "http://127.0.0.1/");
    denied(sb, "http://10.0.0.5/");
    denied(sb, "http://[::1]/");
  }

  @Test
  void aPrivateOrLoopbackLiteralNeedsExplicitApprovalByName() {
    var sb = sandbox(false, "127.0.0.1", "10.0.0.5", "::1");
    allowed(sb, "http://127.0.0.1:9000/");
    allowed(sb, "http://10.0.0.5/");
    allowed(sb, "http://[::1]:8080/");
    denied(sb, "http://127.0.0.2/");
    denied(sb, "http://10.0.0.6/");
  }

  @Test
  void metadataLinkLocalAndSimilarRangesAreNeverAllowedEvenIfListed() {
    var sb =
        sandbox(
            true,
            "169.254.169.254",
            "169.254.10.10",
            "100.100.100.200",
            "fd00:ec2::254",
            "0.0.0.0",
            "224.0.0.1",
            "fe80::1");
    denied(sb, "http://169.254.169.254/latest/meta-data/");
    denied(sb, "http://169.254.10.10/");
    denied(sb, "http://100.100.100.200/");
    denied(sb, "http://[fd00:ec2::254]/");
    denied(sb, "http://0.0.0.0/");
    denied(sb, "http://224.0.0.1/");
    denied(sb, "http://[fe80::1]/");
  }

  @Test
  void ipv4MappedIpv6IsJudgedAsTheEmbeddedIpv4() {
    var sb = sandbox(false, "api.example.com");
    denied(sb, "http://[::ffff:127.0.0.1]/");
    denied(sb, "http://[::ffff:169.254.169.254]/");
    denied(sb, "http://[0:0:0:0:0:ffff:7f00:1]/");
  }

  @Test
  void numericHostDisguisesAreRefused() {
    var sb = sandbox(false, "127.0.0.1", "api.example.com");
    denied(sb, "http://2130706433/"); // 127.0.0.1 as one decimal number
    denied(sb, "http://0x7f.1/");
    denied(sb, "http://0177.0.0.1/"); // octal
    denied(sb, "http://127.1/");
    denied(sb, "http://0x7f000001/");
    denied(sb, "http://127.0.0.1.evil.com/");
    denied(sb, "http://256.1.1.1/");
  }

  @Test
  void nonAsciiAndMalformedHostsAreRefused() {
    var sb = sandbox(false, "api.example.com");
    denied(sb, "http://ａpi.example.com/"); // full-width 'a'
    denied(sb, "http://api.exämple.com/");
    denied(sb, "http://api..example.com/");
    denied(sb, "http://-api.example.com/");
  }

  @Test
  void invalidUrisNeverReachTheSandboxBecauseTheyDoNotParse() {
    for (String broken :
        List.of(
            "http://",
            "http://exa mple.com",
            "http://[::1",
            "ht!tp://x.com",
            "http://a.com\\@evil.com",
            "http://a.com/\u0000",
            "")) {
      try {
        URI parsed = URI.create(broken);
        // Anything that does parse must still be refused if it has no usable host.
        denied(sandbox(false, "api.example.com"), parsed.toString());
      } catch (IllegalArgumentException expected) {
        assertTrue(true);
      }
    }
  }

  @Test
  void anAllowlistedNameThatResolvesToALoopbackOrPrivateAddressIsRefused() {
    var sb =
        sandbox(
            false,
            "rebind.example.com",
            "internal.example.com",
            "v6private.example.com",
            "cgnat.example.com");
    denied(sb, "https://rebind.example.com/");
    denied(sb, "https://internal.example.com/");
    denied(sb, "https://v6private.example.com/");
    denied(sb, "https://cgnat.example.com/");
  }

  @Test
  void anAllowlistedNameThatResolvesToMetadataIsRefusedEvenWhenPrivateNetworksAreOn() {
    var sb = sandbox(true, "meta.example.com", "v6meta.example.com", "alibaba.example.com");
    denied(sb, "https://meta.example.com/");
    denied(sb, "https://v6meta.example.com/");
    denied(sb, "https://alibaba.example.com/");
  }

  @Test
  void allowPrivateNetworksPermitsPrivateTargetsOfApprovedNamesOnly() {
    var sb = sandbox(true, "internal.example.com", "rebind.example.com");
    allowed(sb, "https://internal.example.com/");
    allowed(sb, "https://rebind.example.com/");
    denied(sb, "https://other.example.com/");
  }

  @Test
  void oneBadAddressAmongSeveralRefusesTheHost() {
    denied(sandbox(false, "mixed.example.com"), "https://mixed.example.com/");
  }

  @Test
  void aHostThatCannotBeResolvedCannotBeVerifiedSoItIsRefused() {
    denied(sandbox(false, "gone.example.com"), "https://gone.example.com/");
  }

  @Test
  void publicNamesPass() {
    var sb = sandbox(false, "api.example.com", "*.trusted.org");
    allowed(sb, "https://api.example.com/");
    allowed(sb, "https://news.trusted.org/");
  }

  @Test
  void aSandboxWithNoHostsDeniesEverything() {
    var none =
        new AllowListSandbox(SandboxPolicy.denyAll(), host -> DNS.getOrDefault(host, List.of()));
    denied(none, "https://api.example.com/");
    denied(none, "http://127.0.0.1/");
  }

  @Test
  void denialMessagesNameTheHostButNeverTheQueryOrPath() {
    var sb = sandbox(false, "api.example.com");
    String message =
        assertThrows(
                SandboxViolation.class,
                () ->
                    sb.checkHttpTarget(
                        URI.create("https://evil.com/hook/SECRET-TOKEN?key=SECRET-KEY")))
            .getMessage();
    assertTrue(message.contains("evil.com"));
    assertTrue(!message.contains("SECRET"), message);
  }
}
