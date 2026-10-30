package com.example.linkedinagent.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Erro de negócio estável. Use code para decisões programáticas; message é apenas informativa.")
public record ApiError(
        @Schema(description = "Código estável do erro.", example = "INVALID_REQUEST")
        String code,
        @Schema(description = "Mensagem informativa sanitizada, sem detalhes internos ou do provedor externo.",
                example = "Request validation failed.")
        String message) {
}
