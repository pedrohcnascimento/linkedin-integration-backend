package com.example.linkedinagent.dto;

import com.example.linkedinagent.domain.ContentDraft;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DraftDtos {

    private DraftDtos() {
    }

    @Schema(description = "Dados para criação de um draft textual.")
    public record CreateDraftRequest(
            @NotBlank @Size(max = 3000)
            @Schema(description = "Texto que será publicado no LinkedIn.", example = "Minha primeira publicação pelo LinkedIn Integration Backend.")
            String text,
            @Size(max = 40)
            @Schema(description = "Categoria de mídia. Para drafts textuais, use NONE.", example = "NONE", allowableValues = {"NONE"})
            String mediaCategory,
            @Size(max = 2000)
            @Schema(description = "Deixe vazio ou omita enquanto mídia e artigos não estiverem habilitados.", example = "")
            String originalUrl,
            @Size(max = 300)
            @Schema(description = "Título opcional do draft.", example = "Minha primeira publicação")
            String title) {
    }

    @Schema(description = "Dados para edição parcial de um draft ainda em estado DRAFT.")
    public record EditDraftRequest(
            @Size(max = 3000)
            @Schema(description = "Novo texto; omita para preservar o texto atual.", example = "Conteúdo atualizado")
            String text,
            @Size(max = 40)
            @Schema(description = "Para drafts textuais, use NONE.", example = "NONE", allowableValues = {"NONE"})
            String mediaCategory,
            @Size(max = 2000)
            @Schema(description = "Deixe vazio ou omita enquanto mídia e artigos não estiverem habilitados.", example = "")
            String originalUrl,
            @Size(max = 300)
            @Schema(description = "Novo título; omita para preservar o título atual.", example = "Título atualizado")
            String title) {
    }

    @Schema(description = "Rascunho textual pertencente ao usuário autenticado.")
    public record DraftResponse(
            @Schema(description = "UUID do draft.")
            UUID id,
            @Schema(description = "Texto do rascunho.", example = "Minha primeira publicação pelo LinkedIn Integration Backend.")
            String text,
            @Schema(description = "Categoria de mídia; nesta versão sempre NONE.", allowableValues = {"NONE"}, example = "NONE")
            String mediaCategory,
            @Schema(description = "URL de artigo externo; nulo enquanto mídia e artigos não estiverem habilitados.", nullable = true)
            String originalUrl,
            @Schema(description = "Título opcional do draft.", nullable = true, example = "Minha primeira publicação")
            String title,
            @Schema(description = "DRAFT: editável e aprovável. APPROVED: imutável e pronto para publicação. PUBLISHED: já publicado no LinkedIn.",
                    allowableValues = {"DRAFT", "APPROVED", "PUBLISHED"}, example = "DRAFT")
            String status,
            @Schema(description = "Instante de aprovação em UTC; nulo enquanto o draft estiver em DRAFT.", nullable = true)
            Instant approvedAt,
            @Schema(description = "Instante de criação em UTC.")
            Instant createdAt,
            @Schema(description = "Instante da última alteração em UTC.")
            Instant updatedAt) {

        public static DraftResponse from(ContentDraft draft) {
            return new DraftResponse(
                    draft.getId(),
                    draft.getText(),
                    draft.getMediaCategory(),
                    draft.getOriginalUrl(),
                    draft.getTitle(),
                    draft.getStatus(),
                    draft.getApprovedAt(),
                    draft.getCreatedAt(),
                    draft.getUpdatedAt());
        }
    }

    @Schema(description = "Página de drafts pertencentes ao usuário autenticado.")
    public record DraftPageResponse(
            List<DraftResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {

        public static DraftPageResponse from(Page<ContentDraft> page) {
            return new DraftPageResponse(
                    page.getContent().stream().map(DraftResponse::from).toList(),
                    page.getNumber(),
                    page.getSize(),
                    page.getTotalElements(),
                    page.getTotalPages());
        }
    }
}
