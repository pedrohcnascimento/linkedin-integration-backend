package com.example.linkedinagent.adapter.in.web;

import com.example.linkedinagent.exception.RateLimitExceededException;
import com.example.linkedinagent.exception.RateLimitStoreUnavailableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RateLimitExceptionHandler {

    private static final long STORE_RETRY_AFTER_SECONDS = 5;

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiError> handleLimitExceeded(RateLimitExceededException exception) {
        long retryAfterSeconds = Math.max(1, (long) Math.ceil(exception.getRetryAfter().toMillis() / 1000.0));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
                .body(new ApiError("RATE_LIMITED", "Too many requests. Please retry later."));
    }

    @ExceptionHandler(RateLimitStoreUnavailableException.class)
    public ResponseEntity<ApiError> handleStoreUnavailable(RateLimitStoreUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(STORE_RETRY_AFTER_SECONDS))
                .body(new ApiError("RATE_LIMIT_UNAVAILABLE", "Requests are temporarily unavailable."));
    }

    public record ApiError(String code, String message) {
    }
}
