package io.oryxos.web.api;

import io.oryxos.core.agent.ModelTimeoutException;
import io.oryxos.core.agent.ModelUnavailableException;
import io.oryxos.core.agent.ProfileNotFoundException;
import io.oryxos.core.memory.MemoryStoreException;
import io.oryxos.core.schedule.ScheduleConfigException;
import io.oryxos.core.schedule.ScheduleNotFoundException;
import io.oryxos.core.schedule.SchedulerDisabledException;
import io.oryxos.core.schedule.TaskAlreadyRunningException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Never return server-side exception details to API callers. */
@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** Validation text of our own (cron, zone, message); it never echoes caller input. */
  @ExceptionHandler(ScheduleConfigException.class)
  public ResponseEntity<ApiResponse<Void>> invalidSchedule(ScheduleConfigException exception) {
    return ResponseEntity.badRequest()
        .body(ApiResponse.error(400, "Invalid schedule: " + exception.getMessage()));
  }

  @ExceptionHandler(ScheduleNotFoundException.class)
  public ResponseEntity<ApiResponse<Void>> unknownSchedule(ScheduleNotFoundException exception) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiResponse.error(404, "Unknown schedule"));
  }

  @ExceptionHandler({TaskAlreadyRunningException.class, SchedulerDisabledException.class})
  public ResponseEntity<ApiResponse<Void>> scheduleConflict(RuntimeException exception) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiResponse.error(409, exception.getMessage()));
  }

  @ExceptionHandler(LocalAccessOnlyException.class)
  public ResponseEntity<ApiResponse<Void>> localOnly(LocalAccessOnlyException exception) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ApiResponse.error(403, "Management writes are limited to local access"));
  }

  /** Validation text of our own, see {@link InvalidRequestException}. */
  @ExceptionHandler(InvalidRequestException.class)
  public ResponseEntity<ApiResponse<Void>> invalidRequest(InvalidRequestException exception) {
    return ResponseEntity.badRequest().body(ApiResponse.error(400, exception.getMessage()));
  }

  @ExceptionHandler(ApiNotFoundException.class)
  public ResponseEntity<ApiResponse<Void>> missing(ApiNotFoundException exception) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiResponse.error(404, exception.getMessage()));
  }

  @ExceptionHandler(ProfileNotFoundException.class)
  public ResponseEntity<ApiResponse<Void>> unknownProfile(ProfileNotFoundException exception) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiResponse.error(404, "Unknown agent profile"));
  }

  /** The model could not be reached: the caller may retry later. Provider text is never shown. */
  @ExceptionHandler(ModelUnavailableException.class)
  public ResponseEntity<ApiResponse<Void>> modelUnavailable(ModelUnavailableException exception) {
    log.warn("Model provider unavailable");
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(ApiResponse.error(503, "Model provider unavailable"));
  }

  @ExceptionHandler(ModelTimeoutException.class)
  public ResponseEntity<ApiResponse<Void>> modelTimeout(ModelTimeoutException exception) {
    log.warn("Model provider timed out");
    return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
        .body(ApiResponse.error(504, "Model provider timed out"));
  }

  @ExceptionHandler(MemoryStoreException.class)
  public ResponseEntity<ApiResponse<Void>> memoryUnavailable(MemoryStoreException exception) {
    log.warn("Memory backend failed: {}", exception.getClass().getSimpleName());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(ApiResponse.error(503, "Memory backend unavailable"));
  }

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

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiResponse<Void>> badParameter(MethodArgumentTypeMismatchException e) {
    return ResponseEntity.badRequest().body(ApiResponse.error(400, "Invalid request parameter"));
  }

  @ExceptionHandler(ServletRequestBindingException.class)
  public ResponseEntity<ApiResponse<Void>> badBinding(ServletRequestBindingException e) {
    return ResponseEntity.badRequest().body(ApiResponse.error(400, "Invalid request parameter"));
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
