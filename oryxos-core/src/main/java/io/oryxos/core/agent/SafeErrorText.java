package io.oryxos.core.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an error text into something that is safe to persist and show in an admin console: URLs are
 * cut down to scheme and host, credential-looking pairs and long opaque tokens are masked, control
 * characters are removed and the length is capped. Raw exception messages must never reach the
 * audit tables; callers pass either a class name or text that went through here.
 */
public final class SafeErrorText {
  public static final int MAX_LENGTH = 200;

  private static final Pattern URL =
      Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*)://(?:[^/@\\s]*@)?([^/\\s?#:\"'<>]+)[^\\s\"'<>]*");
  private static final Pattern SCHEME_AUTH =
      Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]+");
  private static final Pattern PAIR =
      Pattern.compile(
          "(?i)\\b(api[_-]?key|access[_-]?token|token|secret|password|passwd|authorization|"
              + "signature|key|sig|auth)\\b\\s*[:=]\\s*[^\\s,;&]+");
  private static final Pattern OPAQUE = Pattern.compile("[A-Za-z0-9_\\-+/=]{24,}");
  private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}+");

  private SafeErrorText() {}

  /** Returns a sanitized, length-capped text, or {@code null} for null or blank input. */
  public static String sanitize(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String text = CONTROL.matcher(raw).replaceAll(" ");
    text = replaceUrls(text);
    text = SCHEME_AUTH.matcher(text).replaceAll("$1 <redacted>");
    text = PAIR.matcher(text).replaceAll("$1=<redacted>");
    text = OPAQUE.matcher(text).replaceAll("<redacted>");
    text = text.replaceAll("\\s+", " ").strip();
    if (text.isEmpty()) {
      return null;
    }
    return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH - 1) + "…";
  }

  /** The only text derived from an exception: its class, never its message. */
  public static String ofException(String prefix, Throwable error) {
    return prefix + " (" + error.getClass().getSimpleName() + ")";
  }

  private static String replaceUrls(String text) {
    Matcher matcher = URL.matcher(text);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      matcher.appendReplacement(
          out, Matcher.quoteReplacement(matcher.group(1) + "://" + matcher.group(2) + "/…"));
    }
    matcher.appendTail(out);
    return out.toString();
  }
}
