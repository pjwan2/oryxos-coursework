package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** What may be persisted as an audit reason: readable, short and free of credentials. */
class SafeErrorTextTest {
  private static void assertClean(String raw, String... forbidden) {
    String safe = SafeErrorText.sanitize(raw);
    for (String secret : forbidden) {
      assertFalse(safe.contains(secret), "leaked '" + secret + "' in: " + safe);
    }
  }

  @Test
  void aPlainReasonIsKeptAsIs() {
    assertEquals(
        "Path is outside the allowed directories",
        SafeErrorText.sanitize("Path is outside the allowed directories"));
  }

  @Test
  void nullAndBlankBecomeNull() {
    assertNull(SafeErrorText.sanitize(null));
    assertNull(SafeErrorText.sanitize("   \n\t "));
  }

  @Test
  void aUrlIsReducedToSchemeAndHostSoPathQueryAndUserinfoDisappear() {
    String safe =
        SafeErrorText.sanitize(
            "POST https://user:pa55@hooks.example.org/services/TOKEN-ABC123?key=SECRETKEY failed");
    assertEquals("POST https://hooks.example.org/… failed", safe);
    assertClean("see http://localhost:8080/a/b?token=abc#frag", "token=abc", "/a/b", "frag");
  }

  @Test
  void credentialLikePairsAreMaskedWhereverTheyAppear() {
    for (String raw :
        new String[] {
          "api_key=sk-live-1234567890 rejected",
          "failed with token: abcdef",
          "password=hunter2 invalid",
          "Authorization: Bearer abc.def.ghi",
          "secret = topsecret and more",
          "bad key=SECRETKEY here"
        }) {
      assertClean(raw, "sk-live", "abcdef", "hunter2", "abc.def", "topsecret", "SECRETKEY");
    }
    assertTrue(SafeErrorText.sanitize("password=hunter2").contains("<redacted>"));
  }

  @Test
  void longOpaqueTokensAreMaskedEvenWithoutAKeyName() {
    String token = "ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345";
    String safe = SafeErrorText.sanitize("upstream replied " + token);
    assertFalse(safe.contains("ABCDEFGHIJKLMNOP"), safe);
    assertTrue(safe.contains("<redacted>"));
  }

  @Test
  void controlCharactersAndLineBreaksAreFlattened() {
    assertEquals("one two three", SafeErrorText.sanitize("one\r\ntwo\u0000\u0007 three"));
  }

  @Test
  void theLengthIsCapped() {
    String safe = SafeErrorText.sanitize("word ".repeat(500));
    assertEquals(SafeErrorText.MAX_LENGTH, safe.length());
    assertTrue(safe.endsWith("…"));
  }

  @Test
  void anExceptionIsDescribedByItsClassNeverByItsMessage() {
    String text =
        SafeErrorText.ofException(
            "Tool threw", new IllegalStateException("https://x.example/?token=SECRET"));
    assertEquals("Tool threw (IllegalStateException)", text);
  }

  @Test
  void aToolCallEventSanitizesItsMessageOnConstruction() {
    ToolCallEvent event =
        new ToolCallEvent(
            "web:u:p",
            "http_get",
            false,
            "ToolReturnedFailure",
            "GET https://api.example.org/v1/x?key=SECRETKEY returned 500",
            5,
            Instant.EPOCH);
    assertEquals("GET https://api.example.org/… returned 500", event.errorMessage());
    assertNull(
        new ToolCallEvent("s", "t", true, null, 1, Instant.EPOCH).errorMessage(),
        "the old six-argument form has no message");
  }
}
