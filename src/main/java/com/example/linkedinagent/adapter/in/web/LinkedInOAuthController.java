package com.example.linkedinagent.adapter.in.web;

import com.example.linkedinagent.adapter.out.security.AppUserPrincipal;
import com.example.linkedinagent.application.linkedin.LinkedInConnection;
import com.example.linkedinagent.application.linkedin.LinkedInOAuthException;
import com.example.linkedinagent.application.linkedin.LinkedInOAuthConfigurationException;
import com.example.linkedinagent.application.linkedin.LinkedInOAuthService;
import com.example.linkedinagent.application.linkedin.LinkedInProviderException;
import com.example.linkedinagent.application.linkedin.TokenEncryptionException;
import com.example.linkedinagent.application.ratelimit.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/linkedin")
@Tag(name = "LinkedIn OAuth", description = "Conexão autorizada com o LinkedIn. O fluxo começa no navegador e termina no callback.")
public class LinkedInOAuthController {

    private final LinkedInOAuthService oauthService;
    private final RateLimitService rateLimitService;

    public LinkedInOAuthController(LinkedInOAuthService oauthService, RateLimitService rateLimitService) {
        this.oauthService = oauthService;
        this.rateLimitService = rateLimitService;
    }

    @GetMapping("/oauth/start")
    @Operation(summary = "Iniciar autorização do LinkedIn", description = """
            Use somente depois de fazer login local com `POST /api/v1/auth/login` e confirmar a sessão em `GET /api/v1/users/me`.

            Esta operação responde com `302 Found` e um header `Location` contendo a URL de autorização do LinkedIn. Copie essa URL completa e abra-a diretamente na barra de endereço do navegador, na mesma sessão em que o login local foi feito. Não use o botão `Execute` do Swagger para seguir o redirect externo: o Swagger usa `fetch` e pode exibir `Failed to fetch` por causa do CORS do LinkedIn.

            Na tela do LinkedIn, faça login, autorize o aplicativo e aguarde o redirecionamento automático para `/api/v1/linkedin/oauth/callback?code=...&state=...`. Gere uma URL nova a cada tentativa; o `state` expira em 10 minutos e só pode ser usado uma vez.

            Pré-requisitos no perfil local: `TOKEN_ENCRYPTION_KEY` configurada, redirect URI `http://localhost:8080/api/v1/linkedin/oauth/callback` cadastrada exatamente no Developer Portal e os produtos **Sign In with LinkedIn using OpenID Connect** e **Share on LinkedIn** habilitados.
            """)
    public ResponseEntity<Void> start(
            @AuthenticationPrincipal AppUserPrincipal user,
            HttpServletRequest request) {
        List<RateLimitService.Limit> limits = new ArrayList<>();
        limits.add(new RateLimitService.Limit(
                "account", user.getId().toString(), 10, Duration.ofHours(1)));
        if (request.getSession(false) != null) {
            limits.add(new RateLimitService.Limit(
                    "session", request.getSession(false).getId(), 8, Duration.ofMinutes(15)));
        }
        rateLimitService.check("oauth-start", limits);
        URI authorizationUri = oauthService.start(user.getId());
        return ResponseEntity.status(HttpStatus.FOUND).location(authorizationUri).build();
    }

    @GetMapping("/oauth/callback")
    @Operation(summary = "Callback do LinkedIn", description = """
            Endpoint chamado pelo LinkedIn após a autorização. Não invente nem edite `code` ou `state`; o navegador deve chegar aqui automaticamente.

            Em caso de sucesso, o access token é cifrado e armazenado localmente e a resposta retorna a conexão sanitizada. Em caso de recusa, o LinkedIn pode enviar `error=...`; nesse caso a API retorna `400`.
            """)
    public ResponseEntity<?> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpServletRequest request) {
        List<RateLimitService.Limit> limits = new ArrayList<>();
        if (state != null && !state.isBlank()) {
            limits.add(new RateLimitService.Limit(
                    "state", state, 6, Duration.ofMinutes(15)));
        }
        if (!limits.isEmpty()) {
            rateLimitService.check("oauth-callback", limits);
        }
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
    @Operation(summary = "Consultar conexão atual", description = "Execute depois que o callback terminar. `connected: true` e `status: ACTIVE` confirmam que o OAuth foi concluído. `DISCONNECTED` significa que ainda não houve uma autorização concluída para o usuário local autenticado.")
    public ConnectionResponse connection(@AuthenticationPrincipal AppUserPrincipal user) {
        return oauthService.connection(user.getId())
                .map(ConnectionResponse::from)
                .orElseGet(ConnectionResponse::disconnected);
    }

    @DeleteMapping("/connection")
    @Operation(summary = "Desconectar LinkedIn", description = "Remove localmente a autorização armazenada para o usuário autenticado. Exige sessão local e CSRF. Esta operação não afirma revogação remota no LinkedIn.")
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
