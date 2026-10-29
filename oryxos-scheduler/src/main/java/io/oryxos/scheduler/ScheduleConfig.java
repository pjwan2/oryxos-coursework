package io.oryxos.scheduler;

import java.time.ZoneId;

/** One validated entry of a Profile's {@code schedules:} list. */
public record ScheduleConfig(String id, String cron, ZoneId zone, String message) {}
