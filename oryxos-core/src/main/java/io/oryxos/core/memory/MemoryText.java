package io.oryxos.core.memory;

/** Input rules shared by every backend so they behave identically. */
public final class MemoryText {
  public static final int MAX_CHARS = 2000;

  private MemoryText() {}

  /**
   * One line, no control characters, 1..{@value #MAX_CHARS} characters. Line breaks become spaces,
   * so a saved fact can never start a new Markdown heading or bullet.
   */
  public static String normalize(String content) {
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content is required");
    }
    StringBuilder out = new StringBuilder(content.length());
    for (int i = 0; i < content.length(); i++) {
      char c = content.charAt(i);
      out.append(Character.isISOControl(c) ? ' ' : c);
    }
    String line = out.toString().strip();
    if (line.isEmpty()) {
      throw new IllegalArgumentException("content is required");
    }
    if (line.length() > MAX_CHARS) {
      throw new IllegalArgumentException("content too long (max " + MAX_CHARS + " characters)");
    }
    return line;
  }

  /** Keyword for archival search: non-blank, trimmed. */
  public static String keyword(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("keyword is required");
    }
    return raw.strip();
  }
}
