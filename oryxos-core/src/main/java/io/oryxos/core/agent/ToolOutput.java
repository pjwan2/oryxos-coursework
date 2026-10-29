package io.oryxos.core.agent;

import java.util.Objects;

/** Tool execution result; failures are explicit instead of being silently swallowed. */
public record ToolOutput(boolean success, String content, String errorMessage, boolean retryable) {
  public ToolOutput {
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(errorMessage, "errorMessage");
  }

  public static ToolOutput ok(String content) {
    return new ToolOutput(true, content, "", false);
  }

  /** Prefix that lets the audit layer tell policy denials from ordinary tool failures. */
  public static final String DENIED_PREFIX = "SANDBOX_DENIED: ";

  public static ToolOutput denied(String reason) {
    return new ToolOutput(false, "", DENIED_PREFIX + reason, false);
  }

  public boolean isDenied() {
    return !success && errorMessage.startsWith(DENIED_PREFIX);
  }

  public static ToolOutput failed(String error, boolean retryable) {
    return new ToolOutput(false, "", error, retryable);
  }
}
