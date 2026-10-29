package io.oryxos.core.agent;

import java.util.regex.Pattern;

/** One deterministic session-id format shared by CLI, HTTP and scheduler. */
public final class SessionIds {
  private static final Pattern SAFE = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

  private SessionIds() {}

  public static String of(String channel, String userId, String profileName) {
    requireSafe(channel);
    requireSafe(userId);
    requireSafe(profileName);
    return channel + ":" + userId + ":" + profileName;
  }

  private static void requireSafe(String segment) {
    if (segment == null || !SAFE.matcher(segment).matches()) {
      throw new IllegalArgumentException("Invalid session identity segment");
    }
  }
}
