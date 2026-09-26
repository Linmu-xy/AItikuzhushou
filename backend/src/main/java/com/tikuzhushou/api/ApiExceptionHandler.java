package com.tikuzhushou.api;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
    log.warn("rejecting request: {} (status=400)", error.getMessage());
    return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", error);
  }

  // A malformed request body must surface as a client error (400) instead of being
  // forwarded to the protected /error dispatch, which previously produced a
  // misleading 401 AUTHENTICATION_REQUIRED for anonymous callers.
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException error) {
    log.warn("request body is not readable JSON: {}", String.valueOf(error.getMessage()).lines().findFirst().orElse(""));
    return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
        new IllegalArgumentException("请求体不是合法的 JSON 或缺少必要字段"));
  }

  @ExceptionHandler(IllegalStateException.class)
  ResponseEntity<Map<String, String>> unavailable(IllegalStateException error) {
    String detail = message(error);
    if (detail.startsWith("EMAIL_CODE_RATE_LIMITED")) return response(HttpStatus.TOO_MANY_REQUESTS, "EMAIL_CODE_RATE_LIMITED", error);
    if (detail.startsWith("EMAIL_DELIVERY_NOT_CONFIGURED")) return response(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_NOT_CONFIGURED", error);
    if (detail.startsWith("ACCOUNT_DISABLED")) return response(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", error);
    if (detail.contains("额度")) return response(HttpStatus.TOO_MANY_REQUESTS, "MODEL_QUOTA_EXCEEDED", error);
    if (detail.startsWith("CONFIG_ENCRYPTION_KEY_MISSING")) {
      log.warn("model credential cannot be managed: encryption key is not configured");
      return response(HttpStatus.SERVICE_UNAVAILABLE, "CONFIG_ENCRYPTION_KEY_MISSING", error);
    }
    if (detail.startsWith("CONFIG_ENCRYPTION_KEY_MISMATCH")) {
      log.warn("model credential decryption failed: stored credential was encrypted with a different key");
      return response(HttpStatus.SERVICE_UNAVAILABLE, "CONFIG_ENCRYPTION_KEY_MISMATCH", error);
    }
    log.error("unexpected service failure: {}", detail, error);
    return response(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", error);
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<Map<String, String>> forbidden(AccessDeniedException error) {
    log.warn("access denied: {}", error.getMessage());
    return response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", error);
  }

  private ResponseEntity<Map<String, String>> response(HttpStatus status, String code, Exception error) {
    return ResponseEntity.status(status).body(Map.of(
        "statusCode", code,
        "message", message(error),
        "httpStatus", Integer.toString(status.value())));
  }

  private String message(Exception error) {
    return error.getMessage() == null || error.getMessage().isBlank() ? "请求处理失败" : error.getMessage();
  }
}
