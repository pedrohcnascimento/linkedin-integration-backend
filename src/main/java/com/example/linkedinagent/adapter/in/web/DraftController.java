package com.example.linkedinagent.adapter.in.web;

import com.example.linkedinagent.adapter.out.persistence.entity.ContentDraftEntity;
import com.example.linkedinagent.adapter.out.persistence.entity.PublicationEntity;
import com.example.linkedinagent.adapter.out.security.AppUserPrincipal;
import com.example.linkedinagent.exception.DraftWorkflowException;
import com.example.linkedinagent.application.publishing.DraftWorkflowService;
import com.example.linkedinagent.exception.LinkedInPublishingException;
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
    @Operation(summary = "Publicar um draft aprovado", description = """
            Publica no LinkedIn um draft APPROVED. Execute primeiro o fluxo OAuth do LinkedIn. Requer o header \
            Idempotency-Key para evitar publicação duplicada.

            Contrato de idempotência e falhas:
            - Replay com a mesma chave depois de PUBLISHED devolve a publicação já salva, com o mesmo status 201, \
            sem nova chamada ao LinkedIn.
            - Repetir a mesma chave depois de FAILED, OUTCOME_UNKNOWN ou durante PUBLISHING retorna 409 \
            PUBLICATION_ALREADY_PROCESSED. Não há retry automático.
            - Uma chave nova para um draft com tentativa PUBLISHING ou OUTCOME_UNKNOWN retorna 409 \
            PUBLICATION_OUTCOME_UNKNOWN até a reconciliação manual no LinkedIn.
            - 502 LINKEDIN_RESULT_UNKNOWN significa que o LinkedIn pode ter aceitado o post; a tentativa fica \
            registrada como OUTCOME_UNKNOWN no histórico. Não reenvie antes de conferir o perfil no LinkedIn.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Publicação concluída ou replay da publicação concluída com a mesma chave (mesmo corpo, sem nova chamada ao LinkedIn).",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PublicationResponse.class),
                            examples = @ExampleObject(name = "Publicação concluída", value = """
                                    {
                                      "id": "0b8f7c52-8d6f-4b8e-9a51-3f0f4a8f2c11",
                                      "draftId": "5d2c1f0e-7a3b-4c9d-8e1f-2a3b4c5d6e7f",
                                      "externalPostId": "urn:li:share:7000000000000000000",
                                      "status": "PUBLISHED",
                                      "failureCode": null,
                                      "publishedAt": "2026-10-30T12:00:01Z",
                                      "createdAt": "2026-10-30T12:00:00Z"
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Header Idempotency-Key ausente, vazio ou com mais de 200 caracteres.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "IDEMPOTENCY_KEY_REQUIRED", value = """
                                    {"code": "IDEMPOTENCY_KEY_REQUIRED", "message": "Idempotency-Key is required."}
                                    """))),
            @ApiResponse(responseCode = "401", description = "Sessão local ausente ou expirada (sem corpo).", content = @Content),
            @ApiResponse(responseCode = "403", description = "Token CSRF ausente ou expirado; execute GET /api/v1/auth/csrf e tente novamente (sem corpo).", content = @Content),
            @ApiResponse(responseCode = "404", description = "Draft não encontrado para o usuário autenticado.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "DRAFT_NOT_FOUND", value = """
                                    {"code": "DRAFT_NOT_FOUND", "message": "Draft was not found."}
                                    """))),
            @ApiResponse(responseCode = "409", description = "A tentativa já foi processada ou requer reconciliação antes de novo envio.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = {
                                    @ExampleObject(name = "PUBLICATION_ALREADY_PROCESSED",
                                            description = "Mesma chave reutilizada depois de FAILED, OUTCOME_UNKNOWN ou durante PUBLISHING.",
                                            value = """
                                                    {"code": "PUBLICATION_ALREADY_PROCESSED", "message": "This idempotent publication request was already processed."}
                                                    """),
                                    @ExampleObject(name = "PUBLICATION_OUTCOME_UNKNOWN",
                                            description = "Chave nova para um draft com tentativa PUBLISHING ou OUTCOME_UNKNOWN não reconciliada.",
                                            value = """
                                                    {"code": "PUBLICATION_OUTCOME_UNKNOWN", "message": "A previous publication attempt is unresolved; reconcile it before retrying."}
                                                    """)
                            })),
            @ApiResponse(responseCode = "422", description = "Draft não está em APPROVED (inclusive draft já PUBLISHED usado com outra chave) ou conexão LinkedIn ativa ausente/expirada.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = {
                                    @ExampleObject(name = "DRAFT_NOT_APPROVED", value = """
                                            {"code": "DRAFT_NOT_APPROVED", "message": "Only an APPROVED draft can be published."}
                                            """),
                                    @ExampleObject(name = "LINKEDIN_CONNECTION_REQUIRED", value = """
                                            {"code": "LINKEDIN_CONNECTION_REQUIRED", "message": "An active LinkedIn connection is required."}
                                            """)
                            })),
            @ApiResponse(responseCode = "429", description = "Limite local de publicação excedido (RATE_LIMITED, com Retry-After) ou limite informado pelo LinkedIn (LINKEDIN_RATE_LIMITED, registrado como FAILED).",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = {
                                    @ExampleObject(name = "RATE_LIMITED", value = """
                                            {"code": "RATE_LIMITED", "message": "Too many requests. Please retry later."}
                                            """),
                                    @ExampleObject(name = "LINKEDIN_RATE_LIMITED", value = """
                                            {"code": "LINKEDIN_RATE_LIMITED", "message": "LinkedIn publication failed."}
                                            """)
                            })),
            @ApiResponse(responseCode = "502", description = "O resultado externo é desconhecido (LINKEDIN_RESULT_UNKNOWN, registrado como OUTCOME_UNKNOWN; reconcilie no LinkedIn antes de tentar novamente) ou o LinkedIn recusou a publicação (código LINKEDIN_*, registrado como FAILED).",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = {
                                    @ExampleObject(name = "LINKEDIN_RESULT_UNKNOWN", value = """
                                            {"code": "LINKEDIN_RESULT_UNKNOWN", "message": "LinkedIn may have accepted the publication; reconcile it before retrying."}
                                            """),
                                    @ExampleObject(name = "LINKEDIN_ACCESS_TOKEN_INVALID",
                                            description = "Exemplo de falha conhecida do provedor; o código varia conforme a resposta do LinkedIn.",
                                            value = """
                                                    {"code": "LINKEDIN_ACCESS_TOKEN_INVALID", "message": "LinkedIn publication failed."}
                                                    """)
                            })),
            @ApiResponse(responseCode = "503", description = "Armazenamento de rate limiting indisponível no perfil prod (fail-closed).",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "RATE_LIMIT_UNAVAILABLE", value = """
                                    {"code": "RATE_LIMIT_UNAVAILABLE", "message": "Requests are temporarily unavailable."}
                                    """)))
    })
    public ResponseEntity<PublicationResponse> publish(
            @AuthenticationPrincipal AppUserPrincipal user,
            @Parameter(description = "UUID real do draft APPROVED retornado pelo POST ou GET /api/v1/drafts.")
            @PathVariable UUID id,
            @Parameter(description = "Chave estável (1 a 200 caracteres) para repetir a mesma operação sem criar outro post. Reutilize a mesma chave apenas para repetir a mesma tentativa.",
                    required = true, example = "publish-2026-10-30-001",
                    schema = @Schema(type = "string", minLength = 1, maxLength = 200))
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        PublicationEntity publication = workflowService.publish(user.getId(), id, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(publication));
    }

    @GetMapping("/publications")
    @Operation(tags = "Publications", summary = "Listar histórico de publicações",
            description = "Retorna somente publicações do usuário autenticado, ordenadas por criação mais recente e UUID descendente. Página começa em 0; tamanho máximo de 50. "
                    + "Inclui tentativas PUBLISHED, FAILED, PUBLISHING e OUTCOME_UNKNOWN; as duas últimas bloqueiam novas publicações do mesmo draft até reconciliação manual. "
                    + "draftId pode ser nulo se o draft associado tiver sido removido.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Página do histórico do usuário.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PublicationPageResponse.class),
                            examples = @ExampleObject(name = "Histórico com sucesso e resultado ambíguo", value = """
                                    {
                                      "content": [
                                        {
                                          "id": "9c1d2e3f-4a5b-4c6d-8e7f-0a1b2c3d4e5f",
                                          "draftId": "7e6d5c4b-3a29-4180-9f7e-6d5c4b3a2918",
                                          "externalPostId": null,
                                          "status": "OUTCOME_UNKNOWN",
                                          "failureCode": "LINKEDIN_RESULT_UNKNOWN",
                                          "publishedAt": null,
                                          "createdAt": "2026-10-30T12:05:00Z"
                                        },
                                        {
                                          "id": "0b8f7c52-8d6f-4b8e-9a51-3f0f4a8f2c11",
                                          "draftId": "5d2c1f0e-7a3b-4c9d-8e1f-2a3b4c5d6e7f",
                                          "externalPostId": "urn:li:share:7000000000000000000",
                                          "status": "PUBLISHED",
                                          "failureCode": null,
                                          "publishedAt": "2026-10-30T12:00:01Z",
                                          "createdAt": "2026-10-30T12:00:00Z"
                                        }
                                      ],
                                      "page": 0,
                                      "size": 20,
                                      "totalElements": 2,
                                      "totalPages": 1
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Parâmetros page/size fora dos limites permitidos.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "INVALID_PAGINATION", value = """
                                    {"code": "INVALID_PAGINATION", "message": "Page must be non-negative and size must be between 1 and 50."}
                                    """))),
            @ApiResponse(responseCode = "401", description = "Sessão local ausente (sem corpo).", content = @Content)
    })
    public PublicationPageResponse listPublications(
            @AuthenticationPrincipal AppUserPrincipal user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 50) {
            throw new DraftWorkflowException("INVALID_PAGINATION",
                    "Page must be non-negative and size must be between 1 and 50.", HttpStatus.BAD_REQUEST);
        }
        Page<PublicationEntity> publications = workflowService.listPublications(user.getId(),
                PageRequest.of(page, size, Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id"))));
        return new PublicationPageResponse(publications.getContent().stream().map(this::toResponse).toList(),
                publications.getNumber(), publications.getSize(),
                publications.getTotalElements(), publications.getTotalPages());
    }

    @ExceptionHandler(DraftWorkflowException.class)
    ResponseEntity<ApiError> handleWorkflow(DraftWorkflowException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ApiError(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(LinkedInPublishingException.class)
    ResponseEntity<ApiError> handleLinkedInPublishing(LinkedInPublishingException exception) {
        if (exception.isOutcomeUnknown()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ApiError("LINKEDIN_RESULT_UNKNOWN",
                            "LinkedIn may have accepted the publication; reconcile it before retrying."));
        }
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
        return new PublicationResponse(publication.getId(),
                publication.getDraft() == null ? null : publication.getDraft().getId(),
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

    @Schema(description = "Página do histórico de publicações do usuário autenticado.")
    public record PublicationPageResponse(java.util.List<PublicationResponse> content, int page, int size,
                                          long totalElements, int totalPages) {
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
    }
}
