package io.oryxos.core.schedule;

import io.oryxos.core.profile.AgentProfile;

/**
 * Keeps the scheduler in step with the agent registry while the application runs. Registration is
 * idempotent: registering an unchanged Profile touches nothing, a changed one cancels the old
 * trigger first. Stored state (enabled flag, run count, history) is never reset.
 */
public interface ProfileScheduleSync {
  /** Validates the Profile's schedules and makes the scheduler match them. */
  void registerProfile(AgentProfile profile);

  /** Stops every trigger of the agent; stored history stays. */
  void unregisterProfile(String profileName);
}
