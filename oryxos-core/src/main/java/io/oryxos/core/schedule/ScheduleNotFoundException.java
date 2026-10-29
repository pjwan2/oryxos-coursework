package io.oryxos.core.schedule;

/** The task id is not a registered schedule. */
public class ScheduleNotFoundException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ScheduleNotFoundException(String taskId) {
    super("Unknown schedule: " + taskId);
  }
}
