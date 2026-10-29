package io.oryxos.core.schedule;

/** The single-flight lock for this task is held; a second run would overlap the first. */
public class TaskAlreadyRunningException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public TaskAlreadyRunningException(String taskId) {
    super("Schedule is already running: " + taskId);
  }
}
