package io.oryxos.core.agent;

/**
 * No profile with that name is loaded. It stays an {@link IllegalArgumentException} so existing
 * callers (CLI, scheduler) behave as before, while the REST layer can answer 404 instead of 400.
 */
public final class ProfileNotFoundException extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;

  public ProfileNotFoundException(String profileName) {
    super("Unknown agent profile: " + profileName);
  }
}
