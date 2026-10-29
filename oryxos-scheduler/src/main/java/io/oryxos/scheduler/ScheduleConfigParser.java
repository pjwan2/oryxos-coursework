package io.oryxos.scheduler;

import io.oryxos.core.agent.SessionIds;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.schedule.ScheduleConfigException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.scheduling.support.CronExpression;

/**
 * Strict reading of {@code schedules:}. Every entry needs an id, a cron (Spring's six-field
 * format), an explicit time zone and a message; unknown keys, duplicate ids, unsafe profile names
 * and schedules that would fire more often than {@link #MIN_INTERVAL} are rejected so a typo stops
 * startup instead of quietly producing a wrong or expensive schedule.
 */
public final class ScheduleConfigParser {
  public static final Duration MIN_INTERVAL = Duration.ofSeconds(30);
  public static final int MAX_MESSAGE_LENGTH = 2000;

  private static final Pattern LOCAL_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Set<String> KEYS = Set.of("id", "cron", "zone", "message");
  private static final Instant PROBE_START = Instant.parse("2026-01-01T00:00:00Z");
  private static final int PROBE_COUNT = 12;

  private ScheduleConfigParser() {}

  /** {@code <profile>.<localId>}: unique across profiles. A dot cannot occur in either part. */
  public static String taskId(String profileName, String localId) {
    return profileName + "." + localId;
  }

  public static List<ScheduleConfig> parse(AgentProfile profile) {
    List<Map<String, Object>> entries = profile.schedules();
    if (entries.isEmpty()) {
      return List.of();
    }
    try {
      SessionIds.of("scheduler", "scheduler", profile.name());
    } catch (IllegalArgumentException e) {
      throw new ScheduleConfigException(
          "Profile '"
              + profile.name()
              + "' has schedules but its name is not usable in a session id ([A-Za-z0-9_-]{1,64})");
    }
    List<ScheduleConfig> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < entries.size(); i++) {
      String where = "Profile '" + profile.name() + "' schedules[" + i + "]";
      ScheduleConfig config = parseEntry(entries.get(i), where);
      if (!seen.add(config.id())) {
        throw new ScheduleConfigException(where + ": duplicate id '" + config.id() + "'");
      }
      result.add(config);
    }
    return List.copyOf(result);
  }

  private static ScheduleConfig parseEntry(Map<String, Object> entry, String where) {
    for (String key : entry.keySet()) {
      if (!KEYS.contains(key)) {
        throw new ScheduleConfigException(
            where + ": unknown key '" + key + "' (allowed: id, cron, zone, message)");
      }
    }
    String id = requiredText(entry, "id", where);
    if (!LOCAL_ID.matcher(id).matches()) {
      throw new ScheduleConfigException(where + ": id must match [A-Za-z0-9_-]{1,64}");
    }
    String cron = requiredText(entry, "cron", where);
    String zone = requiredText(entry, "zone", where);
    String message = requiredText(entry, "message", where);
    try {
      ZoneId zoneId = validate(cron, zone);
      validateMessage(message);
      return new ScheduleConfig(id, cron, zoneId, message);
    } catch (ScheduleConfigException e) {
      throw new ScheduleConfigException(where + ": " + e.getMessage());
    }
  }

  private static String requiredText(Map<String, Object> entry, String key, String where) {
    Object value = entry.get(key);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new ScheduleConfigException(where + ": '" + key + "' is required and must be text");
    }
    return text.strip();
  }

  /** Checks a cron/zone pair and returns the zone. Shared with the update API. */
  public static ZoneId validate(String cron, String zone) {
    ZoneId zoneId;
    try {
      zoneId = ZoneId.of(zone);
    } catch (DateTimeException e) {
      throw new ScheduleConfigException("zone is not a valid time zone id");
    }
    CronExpression expression;
    try {
      expression = CronExpression.parse(cron);
    } catch (IllegalArgumentException e) {
      throw new ScheduleConfigException("cron is not a valid six-field cron expression");
    }
    ZonedDateTime cursor = PROBE_START.atZone(zoneId);
    ZonedDateTime previous = null;
    for (int i = 0; i < PROBE_COUNT; i++) {
      ZonedDateTime next = expression.next(cursor);
      if (next == null) {
        if (previous == null) {
          throw new ScheduleConfigException("cron never fires");
        }
        break;
      }
      if (previous != null && Duration.between(previous, next).compareTo(MIN_INTERVAL) < 0) {
        throw new ScheduleConfigException(
            "cron fires more often than every " + MIN_INTERVAL.toSeconds() + " seconds");
      }
      previous = next;
      cursor = next;
    }
    return zoneId;
  }

  public static void validateMessage(String message) {
    if (message == null || message.isBlank()) {
      throw new ScheduleConfigException("message must not be blank");
    }
    if (message.length() > MAX_MESSAGE_LENGTH) {
      throw new ScheduleConfigException("message is longer than " + MAX_MESSAGE_LENGTH);
    }
  }
}
