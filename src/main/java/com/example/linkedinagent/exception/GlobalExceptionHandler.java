package com.example.linkedinagent.exception;

import com.example.linkedinagent.dto.ApiError;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

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

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ApiError> handleEmailAlreadyRegistered(EmailAlreadyRegisteredException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("EMAIL_ALREADY_REGISTERED", "An account with this email already exists."));
    }

    @ExceptionHandler(PasswordTooLongException.class)
    public ResponseEntity<ApiError> handlePasswordTooLong(PasswordTooLongException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("PASSWORD_TOO_LONG", "Password exceeds the supported UTF-8 byte length."));
    }

    @ExceptionHandler(LinkedInOAuthException.class)
    public ResponseEntity<ApiError> handleOAuthError(LinkedInOAuthException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("LINKEDIN_AUTHORIZATION_FAILED", exception.getMessage()));
    }

    @ExceptionHandler(LinkedInProviderException.class)
    public ResponseEntity<ApiError> handleProviderError(LinkedInProviderException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("LINKEDIN_PROVIDER_ERROR", "LinkedIn provider request failed."));
    }

    @ExceptionHandler(TokenEncryptionException.class)
    public ResponseEntity<ApiError> handleEncryptionError(TokenEncryptionException exception) {
        return ResponseEntity.internalServerError()
                .body(new ApiError("LINKEDIN_CREDENTIAL_STORAGE_ERROR",
                        "LinkedIn credentials could not be stored securely."));
    }

    @ExceptionHandler(LinkedInOAuthConfigurationException.class)
    public ResponseEntity<ApiError> handleConfigurationError(LinkedInOAuthConfigurationException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("LINKEDIN_OAUTH_NOT_CONFIGURED",
                        "LinkedIn authorization is not configured."));
    }

    @ExceptionHandler(DraftWorkflowException.class)
    public ResponseEntity<ApiError> handleWorkflow(DraftWorkflowException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ApiError(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(LinkedInPublishingException.class)
    public ResponseEntity<ApiError> handleLinkedInPublishing(LinkedInPublishingException exception) {
        if (exception.isOutcomeUnknown()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ApiError("LINKEDIN_RESULT_UNKNOWN",
                            "LinkedIn may have accepted the publication; reconcile it before retrying."));
        }
        HttpStatus status = "LINKEDIN_RATE_LIMITED".equals(exception.getFailureCode())
                ? HttpStatus.TOO_MANY_REQUESTS
                : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status)
                .body(new ApiError(exception.getFailureCode(), "LinkedIn publication failed."));
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiError> handleInvalidRequest(Exception exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", "Request validation failed."));
    }
}
