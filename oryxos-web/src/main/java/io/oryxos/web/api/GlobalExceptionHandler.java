package io.oryxos.web.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Never return server-side exception details to API callers. */
@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiResponse<Void>> badRequest(IllegalArgumentException exception) {
    return ResponseEntity.badRequest().body(ApiResponse.error(400, "Invalid request"));
  }

  /**
   * Unreadable JSON is a client error. A common cause is a non-UTF-8 body, e.g. Git Bash curl on
   * Windows sending GBK bytes, so the hint is returned (it contains no server-side detail).
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiResponse<Void>> unreadableBody(HttpMessageNotReadableException e) {
    log.warn("Rejected unreadable request body: {}", e.getClass().getSimpleName());
    return ResponseEntity.badRequest()
        .body(ApiResponse.error(400, "Malformed request body: send valid JSON encoded as UTF-8"));
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ApiResponse<Void>> unsupportedMediaType(
      HttpMediaTypeNotSupportedException e) {
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .body(ApiResponse.error(415, "Unsupported media type: use application/json"));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiResponse<Void>> methodNotAllowed(
      HttpRequestMethodNotSupportedException e) {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .body(ApiResponse.error(405, "Method not allowed"));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiResponse<Void>> expectedStatus(ResponseStatusException exception) {
    return ResponseEntity.status(exception.getStatusCode())
        .body(ApiResponse.error(exception.getStatusCode().value(), "Resource not found"));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiResponse<Void>> notFound(NoResourceFoundException exception) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiResponse.error(404, "Resource not found"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> unexpected(Exception exception) {
    log.error("Unhandled API exception", exception);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiResponse.error(500, "Internal server error"));
  }
}
