package org.modmed.employee.exception;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

/**
 * Centralized exception handler.
 *
 * Resilience4j exceptions are handled here as a safety net in case they
 * propagate past the DepartmentClient fallbacks (e.g., when the controller
 * itself makes a direct service call, or a new client is added without fallbacks).
 * Normal operation: DepartmentClient fallbacks absorb these before they reach here.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EmployeeNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEmployeeNotFound(EmployeeNotFoundException ex) {
        log.warn("Employee not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                error(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage())
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                error(HttpStatus.BAD_REQUEST, "Validation Failed", message)
        );
    }

    // ── Resilience4j exceptions ───────────────────────────────────────────────

    /**
     * Circuit breaker is OPEN — downstream is unhealthy, fast-failing all calls.
     * 503 tells the client "try again later" without burdening the failing service.
     */
    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ErrorResponse> handleCircuitBreakerOpen(CallNotPermittedException ex) {
        log.warn("Circuit breaker OPEN: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                error(HttpStatus.SERVICE_UNAVAILABLE, "Circuit Breaker Open",
                        "Downstream service is temporarily unavailable. Please retry after a moment.")
        );
    }

    /**
     * Rate limit exceeded — caller is sending too many requests.
     * 429 with Retry-After tells the client to back off.
     */
    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<ErrorResponse> handleRateLimitExceeded(RequestNotPermitted ex) {
        log.warn("Rate limit exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
                error(HttpStatus.TOO_MANY_REQUESTS, "Rate Limit Exceeded",
                        "Too many requests. Please slow down and retry.")
        );
    }

    /**
     * Bulkhead full — too many concurrent in-flight calls.
     * 503 is appropriate; the server is temporarily at capacity.
     */
    @ExceptionHandler(BulkheadFullException.class)
    public ResponseEntity<ErrorResponse> handleBulkheadFull(BulkheadFullException ex) {
        log.warn("Bulkhead full: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                error(HttpStatus.SERVICE_UNAVAILABLE, "Service Busy",
                        "Server is currently at capacity. Please retry shortly.")
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("Unexpected error occurred", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                        "An unexpected error occurred")
        );
    }

    private ErrorResponse error(HttpStatus status, String error, String message) {
        return new ErrorResponse(LocalDateTime.now(), status.value(), error, message);
    }
}
