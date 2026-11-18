package com.example.linkedinagent.dto;

import com.example.linkedinagent.domain.LinkedInAuthorization;
import com.example.linkedinagent.domain.LinkedInConnection;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public final class LinkedInOAuthDtos {

    private LinkedInOAuthDtos() {
    }

    @Schema(description = "Estado sanitizado da conexão OAuth 2.0 com o LinkedIn (nunca contém access_token ou refresh_token).")
    public record ConnectionResponse(
            @Schema(description = "Indica se existe autorização registrada para o usuário local (true para ACTIVE ou EXPIRED; false para DISCONNECTED).", example = "true")
            boolean connected,
            @Schema(description = "ACTIVE: autorização válida. EXPIRED: autorização registrada com expiresAt vencido; reconecte via OAuth. DISCONNECTED: nenhuma autorização armazenada.",
                    allowableValues = {"ACTIVE", "EXPIRED", "DISCONNECTED"}, example = "ACTIVE")
            String status,
            @Schema(description = "Identificador OIDC (sub) do membro no LinkedIn; nulo em DISCONNECTED.", nullable = true, example = "yrZCpj2Z12")
            String memberSubject,
            @Schema(description = "Escopos OAuth concedidos; nulo em DISCONNECTED.", nullable = true, example = "openid profile w_member_social")
            String scopes,
            @Schema(description = "Instante de expiração do access token em UTC; nulo em DISCONNECTED.", nullable = true)
            Instant expiresAt,
            @Schema(description = "Instante em que a conexão atual foi autorizada em UTC; nulo em DISCONNECTED.", nullable = true)
            Instant connectedAt) {

        public static ConnectionResponse from(LinkedInConnection connection) {
            return new ConnectionResponse(
                    true,
                    connection.status(),
                    connection.memberSubject(),
                    connection.scopes(),
                    connection.expiresAt(),
                    connection.connectedAt());
        }

        public static ConnectionResponse disconnected() {
            return new ConnectionResponse(
                    false, LinkedInAuthorization.STATUS_DISCONNECTED, null, null, null, null);
        }
    }
}
