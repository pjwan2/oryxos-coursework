package io.oryxos.core.schedule;

/** Raised for changes and manual runs while {@code oryxos.scheduler.enabled=false}. */
public class SchedulerDisabledException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public SchedulerDisabledException() {
    super("Scheduler is disabled (oryxos.scheduler.enabled=false)");
  }
}
