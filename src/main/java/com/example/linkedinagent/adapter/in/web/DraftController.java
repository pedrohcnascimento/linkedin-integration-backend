package com.example.linkedinagent.adapter.in.web;

import com.example.linkedinagent.adapter.out.persistence.entity.ContentDraftEntity;
import com.example.linkedinagent.adapter.out.persistence.entity.PublicationEntity;
import com.example.linkedinagent.adapter.out.security.AppUserPrincipal;
import com.example.linkedinagent.application.publishing.DraftWorkflowException;
import com.example.linkedinagent.application.publishing.DraftWorkflowService;
import com.example.linkedinagent.application.publishing.LinkedInPublishingException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Drafts", description = "Criação, listagem paginada, edição, aprovação e publicação de drafts textuais.")
public class DraftController {

    private final DraftWorkflowService workflowService;

    public DraftController(DraftWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping("/drafts")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Draft textual criado."),
            @ApiResponse(responseCode = "401", description = "Sessão local ausente."),
            @ApiResponse(responseCode = "422", description = "Conteúdo de mídia ainda não suportado; use mediaCategory NONE e deixe originalUrl vazio."),
            @ApiResponse(responseCode = "403", description = "Token CSRF ausente ou expirado; execute GET /api/v1/auth/csrf e tente novamente.")
    })
    public ResponseEntity<DraftResponse> create(
            @AuthenticationPrincipal AppUserPrincipal user,
            @Valid @RequestBody @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "Draft textual válido",
                            value = """
                                    {
                                      "text": "Minha primeira publicação pelo LinkedIn Integration Backend.",
                                      "mediaCategory": "NONE",
                                      "title": "Minha primeira publicação"
                                    }
                                    """))) CreateDraftRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toResponse(workflowService.createDraft(
                        user.getId(), request.text(), request.mediaCategory(), request.originalUrl(), request.title())));
    }

    @GetMapping("/drafts")
    @Operation(summary = "Listar drafts do usuário", description = "Requer login local. Retorna somente drafts pertencentes à sessão autenticada, ordenados por atualização mais recente. A página começa em 0 e o tamanho máximo é 50.")
    public DraftPageResponse list(
            @AuthenticationPrincipal AppUserPrincipal user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 50) {
            throw new DraftWorkflowException("INVALID_PAGINATION",
                    "Page must be non-negative and size must be between 1 and 50.", HttpStatus.BAD_REQUEST);
        }
        Page<ContentDraftEntity> drafts = workflowService.list(user.getId(),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt")));
        return new DraftPageResponse(drafts.getContent().stream().map(this::toResponse).toList(),
                drafts.getNumber(), drafts.getSize(), drafts.getTotalElements(), drafts.getTotalPages());
    }

    @PatchMapping("/drafts/{id}")
    @Operation(summary = "Editar um draft", description = "Atualiza parcialmente texto ou título. Use no caminho o id real retornado pelo POST /api/v1/drafts ou GET /api/v1/drafts; o UUID 3fa85f64-5717-4562-b3fc-2c963f66afa6 exibido pelo Swagger é apenas um exemplo. Só funciona enquanto o draft estiver em DRAFT; depois da aprovação ele não pode mais ser editado.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Draft atualizado."),
            @ApiResponse(responseCode = "404", description = "Draft não encontrado para o usuário autenticado; confirme o UUID retornado pelo POST ou GET."),
            @ApiResponse(responseCode = "409", description = "O draft já foi aprovado e não pode mais ser editado."),
            @ApiResponse(responseCode = "422", description = "Conteúdo de mídia ainda não suportado."),
            @ApiResponse(responseCode = "403", description = "Token CSRF ausente ou expirado.")
    })
    public DraftResponse update(
            @AuthenticationPrincipal AppUserPrincipal user,
            @Parameter(description = "UUID real do draft retornado pelo POST ou GET. Não use o UUID de exemplo do Swagger.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @PathVariable UUID id,
            @Valid @RequestBody @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "Edição textual válida",
                            value = """
                                    {
                                      "text": "Conteúdo atualizado",
                                      "title": "Título atualizado"
                                    }
                                    """))) EditDraftRequest request) {
        return toResponse(workflowService.update(user.getId(), id, request.text(), request.mediaCategory(),
                request.originalUrl(), request.title()));
    }

    @PostMapping("/drafts/{id}/approve")
    @Operation(summary = "Aprovar um draft", description = "Muda o draft de DRAFT para APPROVED. Depois disso o conteúdo fica imutável e pode ser publicado.")
    public DraftResponse approve(
            @AuthenticationPrincipal AppUserPrincipal user,
            @PathVariable UUID id) {
        return toResponse(workflowService.approve(user.getId(), id));
    }

    @PostMapping("/drafts/{id}/publish")
    @Operation(summary = "Publicar um draft aprovado", description = "Publica no LinkedIn um draft APPROVED. Execute primeiro o fluxo OAuth do LinkedIn. Requer o header Idempotency-Key para evitar publicação duplicada.")
    public ResponseEntity<PublicationResponse> publish(
            @AuthenticationPrincipal AppUserPrincipal user,
            @PathVariable UUID id,
            @Parameter(description = "Chave estável para repetir a mesma operação sem criar outro post.", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        PublicationEntity publication = workflowService.publish(user.getId(), id, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(publication));
    }

    @ExceptionHandler(DraftWorkflowException.class)
    ResponseEntity<ApiError> handleWorkflow(DraftWorkflowException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ApiError(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(LinkedInPublishingException.class)
    ResponseEntity<ApiError> handleLinkedInPublishing(LinkedInPublishingException exception) {
        HttpStatus status = "LINKEDIN_RATE_LIMITED".equals(exception.getFailureCode())
                ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status)
                .body(new ApiError(exception.getFailureCode(), "LinkedIn publication failed."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> handleInvalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "Request validation failed."));
    }

    private DraftResponse toResponse(ContentDraftEntity draft) {
        return new DraftResponse(draft.getId(), draft.getText(), draft.getMediaCategory(), draft.getOriginalUrl(),
                draft.getTitle(), draft.getStatus(),
                draft.getApprovedAt(), draft.getCreatedAt(), draft.getUpdatedAt());
    }

    private PublicationResponse toResponse(PublicationEntity publication) {
        return new PublicationResponse(publication.getId(), publication.getDraft().getId(),
                publication.getExternalPostId(), publication.getStatus(), publication.getFailureCode(),
                publication.getPublishedAt(), publication.getCreatedAt());
    }

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

    public record DraftResponse(UUID id, String text, String mediaCategory, String originalUrl, String title, String status,
                                Instant approvedAt, Instant createdAt, Instant updatedAt) {
    }

    public record DraftPageResponse(java.util.List<DraftResponse> content, int page, int size,
                                    long totalElements, int totalPages) {
    }

    public record PublicationResponse(UUID id, UUID draftId, String externalPostId, String status,
                                      String failureCode, Instant publishedAt, Instant createdAt) {
    }

    public record ApiError(String code, String message) {
    }
}
