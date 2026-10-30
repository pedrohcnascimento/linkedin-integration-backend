package com.example.linkedinagent.dto;

import com.example.linkedinagent.domain.AppUser;
import com.example.linkedinagent.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
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

        public static UserResponse from(AppUser user) {
            return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
        }

        public static UserResponse from(AppUserPrincipal principal) {
            return new UserResponse(
                    principal.getId(), principal.getEmail(), principal.getDisplayName(), principal.getCreatedAt());
        }
    }
}
