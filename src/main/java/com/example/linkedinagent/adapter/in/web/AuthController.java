package com.example.linkedinagent.adapter.in.web;

import com.example.linkedinagent.adapter.out.security.AppUserPrincipal;
import com.example.linkedinagent.application.auth.EmailAlreadyRegisteredException;
import com.example.linkedinagent.application.auth.PasswordTooLongException;
import com.example.linkedinagent.application.auth.RegisteredUser;
import com.example.linkedinagent.application.auth.RegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Validated
public class AuthController {

    private final RegistrationService registrationService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final CsrfTokenRepository csrfTokenRepository;

    public AuthController(
            RegistrationService registrationService,
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            CsrfTokenRepository csrfTokenRepository) {
        this.registrationService = registrationService;
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    @GetMapping("/auth/csrf")
    public CsrfResponse csrf(CsrfToken csrfToken) {
        return new CsrfResponse(csrfToken.getHeaderName(), csrfToken.getToken());
    }

    @PostMapping("/auth/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        RegisteredUser user =
                registrationService.register(request.email(), request.displayName(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new UserResponse(user.id(), user.email(), user.displayName(), user.createdAt()));
    }

    @PostMapping("/auth/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
        } catch (AuthenticationException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ApiError("AUTHENTICATION_FAILED", "Email or password is incorrect."));
        }

        sessionAuthenticationStrategy.onAuthentication(authentication, httpRequest, httpResponse);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);
        csrfTokenRepository.saveToken(null, httpRequest, httpResponse);

        AppUserPrincipal user = (AppUserPrincipal) authentication.getPrincipal();
        return ResponseEntity.ok(new UserResponse(
                user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt()));
    }

    @GetMapping("/users/me")
    public UserResponse currentUser(@AuthenticationPrincipal AppUserPrincipal user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
    }

    @ExceptionHandler({EmailAlreadyRegisteredException.class, PasswordTooLongException.class,
            DataIntegrityViolationException.class})
    private ResponseEntity<ApiError> handleRegistrationErrors(RuntimeException exception) {
        if (exception instanceof EmailAlreadyRegisteredException
                || exception instanceof DataIntegrityViolationException) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiError("EMAIL_ALREADY_REGISTERED", "An account with this email already exists."));
        }
        return ResponseEntity.badRequest()
                .body(new ApiError("PASSWORD_TOO_LONG", "Password exceeds the supported UTF-8 byte length."));
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank @Size(min = 12, max = 72) String password) {
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 72) String password) {
    }

    public record CsrfResponse(String headerName, String token) {
    }

    public record UserResponse(UUID id, String email, String displayName, Instant createdAt) {
    }

    public record ApiError(String code, String message) {
    }
}
