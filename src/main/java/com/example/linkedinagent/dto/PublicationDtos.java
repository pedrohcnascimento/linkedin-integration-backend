package com.example.linkedinagent.dto;

import com.example.linkedinagent.domain.Publication;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PublicationDtos {

    private PublicationDtos() {
    }

    @Schema(description = "Tentativa de publicação registrada para um draft.")
    public record PublicationResponse(
            @Schema(description = "UUID da tentativa de publicação.")
            UUID id,
            @Schema(description = "UUID do draft publicado; nulo se o draft associado tiver sido removido.", nullable = true)
            UUID draftId,
            @Schema(description = "Identificador retornado pelo LinkedIn no header x-restli-id (ou registrado na reconciliação manual, quando disponível); presente somente em PUBLISHED.",
                    nullable = true, example = "urn:li:share:7000000000000000000")
            String externalPostId,
            @Schema(description = """
                    PUBLISHING: reserva gravada antes da chamada ao LinkedIn (em curso ou interrompida; bloqueia novas tentativas). \
                    PUBLISHED: LinkedIn confirmou o post. \
                    FAILED: falha conhecida do LinkedIn ou tentativa encerrada por reconciliação manual; a mesma chave não é reenviada. \
                    OUTCOME_UNKNOWN: o LinkedIn pode ter aceitado o post; exige reconciliação manual antes de qualquer reenvio.""",
                    allowableValues = {"PUBLISHING", "PUBLISHED", "FAILED", "OUTCOME_UNKNOWN"}, example = "PUBLISHED")
            String status,
            @Schema(description = "Código interno da falha (LINKEDIN_*, LINKEDIN_RESULT_UNKNOWN ou RECONCILED_NOT_PUBLISHED); nulo em PUBLISHING e PUBLISHED.",
                    nullable = true, example = "LINKEDIN_RESULT_UNKNOWN")
            String failureCode,
            @Schema(description = "Instante da confirmação de publicação; nulo fora de PUBLISHED.", nullable = true)
            Instant publishedAt,
            @Schema(description = "Instante em que a tentativa foi reservada.")
            Instant createdAt) {

        public static PublicationResponse from(Publication publication) {
            return new PublicationResponse(
                    publication.getId(),
                    publication.getDraft() == null ? null : publication.getDraft().getId(),
                    publication.getExternalPostId(),
                    publication.getStatus(),
                    publication.getFailureCode(),
                    publication.getPublishedAt(),
                    publication.getCreatedAt());
        }
    }

    @Schema(description = "Página do histórico de publicações do usuário autenticado.")
    public record PublicationPageResponse(
            List<PublicationResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {

        public static PublicationPageResponse from(Page<Publication> page) {
            return new PublicationPageResponse(
                    page.getContent().stream().map(PublicationResponse::from).toList(),
                    page.getNumber(),
                    page.getSize(),
                    page.getTotalElements(),
                    page.getTotalPages());
        }
    }
}
