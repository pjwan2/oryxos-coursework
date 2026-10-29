package io.oryxos.core.schedule;

/** Runtime edit of a schedule; null fields are left unchanged. */
public record ScheduleUpdate(Boolean enabled, String cron, String zone, String message) {
  public boolean isEmpty() {
    return enabled == null && cron == null && zone == null && message == null;
  }
}
