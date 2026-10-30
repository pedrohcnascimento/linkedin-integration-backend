package com.example.linkedinagent.adapter.in.web;

import com.example.linkedinagent.adapter.out.security.AppUserPrincipal;
import com.example.linkedinagent.exception.EmailAlreadyRegisteredException;
import com.example.linkedinagent.exception.PasswordTooLongException;
import com.example.linkedinagent.application.auth.RegisteredUser;
import com.example.linkedinagent.application.auth.RegistrationService;
import com.example.linkedinagent.application.ratelimit.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Authentication", description = "Conta local, sessão e token CSRF usado pela UI do Swagger.")
public class AuthController {

    private final RegistrationService registrationService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final CsrfTokenRepository csrfTokenRepository;
    private final RateLimitService rateLimitService;

    public AuthController(
            RegistrationService registrationService,
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            CsrfTokenRepository csrfTokenRepository,
            RateLimitService rateLimitService) {
        this.registrationService = registrationService;
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.csrfTokenRepository = csrfTokenRepository;
        this.rateLimitService = rateLimitService;
    }

    @GetMapping("/auth/csrf")
    @Operation(summary = "Obter token CSRF", description = "Execute antes de requisições POST, PATCH ou DELETE. No perfil local o token também é gravado no cookie XSRF-TOKEN e o Swagger o envia no header X-XSRF-TOKEN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token CSRF atual e nome do header esperado.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = CsrfResponse.class),
                            examples = @ExampleObject(name = "Token CSRF", value = """
                                    {"headerName": "X-XSRF-TOKEN", "token": "c4f1d2e3-89ab-4cde-9012-3456789abcde"}
                                    """)))
    })
    public CsrfResponse csrf(CsrfToken csrfToken) {
        return new CsrfResponse(csrfToken.getHeaderName(), csrfToken.getToken());
    }

    @PostMapping("/auth/register")
    @Operation(summary = "Criar conta local", description = "Cria a conta usada para autenticar os endpoints protegidos e manter a sessão do Swagger. Exige token CSRF válido.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Conta local criada.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = UserResponse.class),
                            examples = @ExampleObject(name = "Conta criada", value = """
                                    {
                                      "id": "1a2b3c4d-5e6f-4789-8012-3456789abcde",
                                      "email": "pessoa@exemplo.com",
                                      "displayName": "Pessoa Exemplo",
                                      "createdAt": "2026-10-30T12:00:00Z"
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Dados inválidos (formato de e-mail, campos obrigatórios ou limites de tamanho da senha).",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = {
                                    @ExampleObject(name = "INVALID_REQUEST", value = """
                                            {"code": "INVALID_REQUEST", "message": "Request validation failed."}
                                            """),
                                    @ExampleObject(name = "PASSWORD_TOO_LONG", value = """
                                            {"code": "PASSWORD_TOO_LONG", "message": "Password exceeds the supported UTF-8 byte length."}
                                            """)
                            })),
            @ApiResponse(responseCode = "403", description = "Token CSRF ausente ou inválido; execute GET /api/v1/auth/csrf e tente novamente (sem corpo).", content = @Content),
            @ApiResponse(responseCode = "409", description = "E-mail já cadastrado (comparação case-insensitive).",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "EMAIL_ALREADY_REGISTERED", value = """
                                    {"code": "EMAIL_ALREADY_REGISTERED", "message": "An account with this email already exists."}
                                    """))),
            @ApiResponse(responseCode = "429", description = "Limite de cadastros excedido por IP (10/h) ou conta (3/24h).",
                    headers = @Header(name = "Retry-After", description = "Segundos restantes até nova tentativa.", schema = @Schema(type = "string", example = "60")),
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "RATE_LIMITED", value = """
                                    {"code": "RATE_LIMITED", "message": "Too many requests. Please retry later."}
                                    """))),
            @ApiResponse(responseCode = "503", description = "Armazenamento de rate limiting indisponível no perfil prod (fail-closed).",
                    headers = @Header(name = "Retry-After", description = "Segundos sugeridos antes de nova tentativa.", schema = @Schema(type = "string", example = "5")),
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "RATE_LIMIT_UNAVAILABLE", value = """
                                    {"code": "RATE_LIMIT_UNAVAILABLE", "message": "Requests are temporarily unavailable."}
                                    """)))
    })
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        rateLimitService.check("auth-register", List.of(new RateLimitService.Limit(
                "account", request.email().trim().toLowerCase(Locale.ROOT), 3, Duration.ofDays(1))));
        RegisteredUser user =
                registrationService.register(request.email(), request.displayName(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new UserResponse(user.id(), user.email(), user.displayName(), user.createdAt()));
    }

    @PostMapping("/auth/login")
    @Operation(summary = "Entrar na sessão local", description = "Autentica com e-mail e senha locais. Após o login, o cookie JSESSIONID é rotacionado e um novo cookie CSRF é emitido automaticamente no perfil local.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sessão local autenticada.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = UserResponse.class),
                            examples = @ExampleObject(name = "Sessão iniciada", value = """
                                    {
                                      "id": "1a2b3c4d-5e6f-4789-8012-3456789abcde",
                                      "email": "pessoa@exemplo.com",
                                      "displayName": "Pessoa Exemplo",
                                      "createdAt": "2026-10-30T12:00:00Z"
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Corpo JSON malformado ou campos inválidos.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "INVALID_REQUEST", value = """
                                    {"code": "INVALID_REQUEST", "message": "Request validation failed."}
                                    """))),
            @ApiResponse(responseCode = "401", description = "Credenciais incorretas (mensagem genérica sem revelar se o e-mail existe).",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "AUTHENTICATION_FAILED", value = """
                                    {"code": "AUTHENTICATION_FAILED", "message": "Email or password is incorrect."}
                                    """))),
            @ApiResponse(responseCode = "403", description = "Token CSRF ausente ou inválido; execute GET /api/v1/auth/csrf e tente novamente (sem corpo).", content = @Content),
            @ApiResponse(responseCode = "429", description = "Limite de tentativas excedido por IP (30/15min) ou conta (8/15min).",
                    headers = @Header(name = "Retry-After", description = "Segundos restantes até nova tentativa.", schema = @Schema(type = "string", example = "37")),
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "RATE_LIMITED", value = """
                                    {"code": "RATE_LIMITED", "message": "Too many requests. Please retry later."}
                                    """))),
            @ApiResponse(responseCode = "503", description = "Armazenamento de rate limiting indisponível no perfil prod (fail-closed).",
                    headers = @Header(name = "Retry-After", description = "Segundos sugeridos antes de nova tentativa.", schema = @Schema(type = "string", example = "5")),
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "RATE_LIMIT_UNAVAILABLE", value = """
                                    {"code": "RATE_LIMIT_UNAVAILABLE", "message": "Requests are temporarily unavailable."}
                                    """)))
    })
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        rateLimitService.check("auth-login", List.of(new RateLimitService.Limit(
                "account", request.email().trim().toLowerCase(Locale.ROOT), 8, Duration.ofMinutes(15))));
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
        // Rotate the CSRF token after authentication and expose the fresh cookie.
        // The local Swagger UI reads this cookie when executing state-changing requests.
        CsrfToken freshCsrfToken = csrfTokenRepository.generateToken(httpRequest);
        csrfTokenRepository.saveToken(freshCsrfToken, httpRequest, httpResponse);

        AppUserPrincipal user = (AppUserPrincipal) authentication.getPrincipal();
        return ResponseEntity.ok(new UserResponse(
                user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt()));
    }

    @GetMapping("/users/me")
    @Operation(summary = "Consultar usuário autenticado", description = "Retorna os dados sanitizados da conta associada à sessão HTTP atual.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Conta local autenticada.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "401", description = "Sessão local ausente ou expirada (sem corpo).", content = @Content)
    })
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

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    private ResponseEntity<ApiError> handleInvalidRequest(Exception exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", "Request validation failed."));
    }

    @Schema(description = "Dados para cadastro de conta local.")
    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254)
            @Schema(description = "E-mail da conta local (normalizado em minúsculas).", example = "pessoa@exemplo.com")
            String email,
            @NotBlank @Size(max = 120)
            @Schema(description = "Nome de exibição.", example = "Pessoa Exemplo")
            String displayName,
            @NotBlank @Size(min = 12, max = 72)
            @Schema(description = "Senha local (12 a 72 caracteres e no máximo 72 bytes em UTF-8).", example = "senha-local-segura-123")
            String password) {
    }

    @Schema(description = "Credenciais para autenticação na sessão local.")
    public record LoginRequest(
            @NotBlank @Email @Size(max = 254)
            @Schema(description = "E-mail cadastrado.", example = "pessoa@exemplo.com")
            String email,
            @NotBlank @Size(max = 72)
            @Schema(description = "Senha da conta local.", example = "senha-local-segura-123")
            String password) {
    }

    @Schema(description = "Token CSRF e nome do header exigido em requisições que alteram estado.")
    public record CsrfResponse(
            @Schema(description = "Nome do header HTTP no qual o token deve ser enviado.", example = "X-XSRF-TOKEN")
            String headerName,
            @Schema(description = "Valor do token CSRF vinculado à sessão/cookie.", example = "c4f1d2e3-89ab-4cde-9012-3456789abcde")
            String token) {
    }

    @Schema(description = "Representação sanitizada da conta local autenticada (nunca inclui hash ou senha).")
    public record UserResponse(
            @Schema(description = "UUID da conta local.")
            UUID id,
            @Schema(description = "E-mail normalizado da conta.", example = "pessoa@exemplo.com")
            String email,
            @Schema(description = "Nome de exibição.", example = "Pessoa Exemplo")
            String displayName,
            @Schema(description = "Instante de criação da conta em UTC.")
            Instant createdAt) {
    }
}
