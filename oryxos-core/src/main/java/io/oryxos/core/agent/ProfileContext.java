package io.oryxos.core.agent;

import io.oryxos.core.profile.AgentProfile;
import java.util.Objects;

/** Request-scoped profile for tools. Must always be cleared in finally. */
public final class ProfileContext {
  private static final ThreadLocal<AgentProfile> CURRENT = new ThreadLocal<>();

  private ProfileContext() {}

  public static void set(AgentProfile profile) {
    CURRENT.set(Objects.requireNonNull(profile));
  }

  public static AgentProfile current() {
    return CURRENT.get();
  }

  public static void clear() {
    CURRENT.remove();
  }
}
