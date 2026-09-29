package com.example.linkedinagent.adapter.in.web;

import com.example.linkedinagent.adapter.out.security.AppUserPrincipal;
import com.example.linkedinagent.application.linkedin.LinkedInConnection;
import com.example.linkedinagent.application.linkedin.LinkedInOAuthException;
import com.example.linkedinagent.application.linkedin.LinkedInOAuthConfigurationException;
import com.example.linkedinagent.application.linkedin.LinkedInOAuthService;
import com.example.linkedinagent.application.linkedin.LinkedInProviderException;
import com.example.linkedinagent.application.linkedin.TokenEncryptionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/linkedin")
public class LinkedInOAuthController {

    private final LinkedInOAuthService oauthService;

    public LinkedInOAuthController(LinkedInOAuthService oauthService) {
        this.oauthService = oauthService;
    }

    @GetMapping("/oauth/start")
    public ResponseEntity<Void> start(@AuthenticationPrincipal AppUserPrincipal user) {
        URI authorizationUri = oauthService.start(user.getId());
        return ResponseEntity.status(HttpStatus.FOUND).location(authorizationUri).build();
    }

    @GetMapping("/oauth/callback")
    public ResponseEntity<?> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error) {
        if (error != null) {
            oauthService.rejectAuthorization(state);
            return ResponseEntity.badRequest()
                    .body(new ApiError("LINKEDIN_AUTHORIZATION_DENIED",
                            "LinkedIn authorization was not completed."));
        }
        LinkedInConnection connection = oauthService.complete(state, code);
        return ResponseEntity.ok(ConnectionResponse.from(connection));
    }

    @GetMapping("/connection")
    public ConnectionResponse connection(@AuthenticationPrincipal AppUserPrincipal user) {
        return oauthService.connection(user.getId())
                .map(ConnectionResponse::from)
                .orElseGet(ConnectionResponse::disconnected);
    }

    @DeleteMapping("/connection")
    public ResponseEntity<Void> disconnect(@AuthenticationPrincipal AppUserPrincipal user) {
        oauthService.disconnect(user.getId());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(LinkedInOAuthException.class)
    public ResponseEntity<ApiError> handleOAuthError(LinkedInOAuthException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("LINKEDIN_AUTHORIZATION_FAILED", exception.getMessage()));
    }

    @ExceptionHandler(LinkedInProviderException.class)
    public ResponseEntity<ApiError> handleProviderError(LinkedInProviderException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("LINKEDIN_PROVIDER_ERROR", exception.getMessage()));
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

    public record ConnectionResponse(
            boolean connected,
            String status,
            String memberSubject,
            String scopes,
            Instant expiresAt,
            Instant connectedAt) {

        private static ConnectionResponse from(LinkedInConnection connection) {
            return new ConnectionResponse(
                    true,
                    connection.status(),
                    connection.memberSubject(),
                    connection.scopes(),
                    connection.expiresAt(),
                    connection.connectedAt());
        }

        private static ConnectionResponse disconnected() {
            return new ConnectionResponse(false, "DISCONNECTED", null, null, null, null);
        }
    }

    public record ApiError(String code, String message) {
    }
}
