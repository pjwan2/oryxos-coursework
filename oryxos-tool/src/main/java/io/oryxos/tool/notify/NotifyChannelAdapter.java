package io.oryxos.tool.notify;

/** One delivery mechanism (webhook, email, ...). Failures must be reported, never hidden. */
public interface NotifyChannelAdapter {
  /** The Profile notify_channels type this adapter handles, e.g. "webhook". */
  String type();

  NotifyResult send(NotifyTarget target, String title, String message);

  record NotifyResult(boolean success, String detail, boolean retryable) {
    public static NotifyResult ok() {
      return new NotifyResult(true, "delivered", false);
    }

    public static NotifyResult failed(String detail, boolean retryable) {
      return new NotifyResult(false, detail, retryable);
    }
  }
}
