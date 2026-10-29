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

  public static ToolOutput failed(String error, boolean retryable) {
    return new ToolOutput(false, "", error, retryable);
  }
}
