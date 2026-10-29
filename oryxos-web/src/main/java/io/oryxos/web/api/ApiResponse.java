package io.oryxos.web.api;

import java.time.Instant;

/** Single response envelope for current and future REST endpoints. */
public record ApiResponse<T>(int code, String message, T data, Instant timestamp) {
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(0, "ok", data, Instant.now());
  }

  public static ApiResponse<Void> error(int code, String message) {
    return new ApiResponse<>(code, message, null, Instant.now());
  }
}
