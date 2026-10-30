package io.oryxos.scheduler;

import io.oryxos.core.profile.AgentDefinitionException;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.profile.ProfileValidator;
import io.oryxos.core.schedule.ScheduleConfigException;

/**
 * Rejects an agent whose {@code schedules:} would not be accepted by the scheduler (bad cron,
 * unknown time zone, duplicate id, too frequent). Runs before the agent is registered, so a wrong
 * schedule is reported to its author instead of surfacing later inside the scheduler.
 */
public final class ScheduleProfileValidator implements ProfileValidator {
  @Override
  public void validate(AgentProfile profile) {
    try {
      ScheduleConfigParser.parse(profile);
    } catch (ScheduleConfigException invalid) {
      throw new AgentDefinitionException("schedules: " + invalid.getMessage(), invalid);
    }
  }
}
