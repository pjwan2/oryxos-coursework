package io.oryxos.core.schedule;

/** A schedule definition is unusable. Raised at startup (and by the update API) with the reason. */
public class ScheduleConfigException extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;

  public ScheduleConfigException(String message) {
    super(message);
  }
}
