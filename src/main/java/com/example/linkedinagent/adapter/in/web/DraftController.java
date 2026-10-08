package com.example.linkedinagent.adapter.in.web;

import com.example.linkedinagent.adapter.out.persistence.entity.ContentDraftEntity;
import com.example.linkedinagent.adapter.out.persistence.entity.PublicationEntity;
import com.example.linkedinagent.adapter.out.security.AppUserPrincipal;
import com.example.linkedinagent.application.publishing.DraftWorkflowException;
import com.example.linkedinagent.application.publishing.DraftWorkflowService;
import com.example.linkedinagent.application.publishing.LinkedInPublishingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DraftController {

    private final DraftWorkflowService workflowService;

    public DraftController(DraftWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping("/drafts")
    public ResponseEntity<DraftResponse> create(
            @AuthenticationPrincipal AppUserPrincipal user,
            @Valid @RequestBody CreateDraftRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toResponse(workflowService.createDraft(
                        user.getId(), request.text(), request.mediaCategory(), request.originalUrl(), request.title())));
    }

    @PostMapping("/drafts/{id}/approve")
    public DraftResponse approve(
            @AuthenticationPrincipal AppUserPrincipal user,
            @PathVariable UUID id) {
        return toResponse(workflowService.approve(user.getId(), id));
    }

    @PostMapping("/drafts/{id}/publish")
    public ResponseEntity<PublicationResponse> publish(
            @AuthenticationPrincipal AppUserPrincipal user,
            @PathVariable UUID id,
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
        return new DraftResponse(draft.getId(), draft.getText(), draft.getMediaCategory(), draft.getStatus(),
                draft.getApprovedAt(), draft.getCreatedAt(), draft.getUpdatedAt());
    }

    private PublicationResponse toResponse(PublicationEntity publication) {
        return new PublicationResponse(publication.getId(), publication.getDraft().getId(),
                publication.getExternalPostId(), publication.getStatus(), publication.getFailureCode(),
                publication.getPublishedAt(), publication.getCreatedAt());
    }

    public record CreateDraftRequest(
            @NotBlank @Size(max = 3000) String text,
            @Size(max = 40) String mediaCategory,
            @Size(max = 2000) String originalUrl,
            @Size(max = 300) String title) {
    }

    public record DraftResponse(UUID id, String text, String mediaCategory, String status,
                                Instant approvedAt, Instant createdAt, Instant updatedAt) {
    }

    public record PublicationResponse(UUID id, UUID draftId, String externalPostId, String status,
                                      String failureCode, Instant publishedAt, Instant createdAt) {
    }

    public record ApiError(String code, String message) {
    }
}
