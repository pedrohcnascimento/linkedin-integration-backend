package com.example.linkedinagent.application.publishing;

import com.example.linkedinagent.adapter.out.persistence.entity.AppUserEntity;
import com.example.linkedinagent.adapter.out.persistence.entity.ContentDraftEntity;
import com.example.linkedinagent.adapter.out.persistence.entity.PublicationEntity;
import com.example.linkedinagent.adapter.out.persistence.repository.ContentDraftRepository;
import com.example.linkedinagent.adapter.out.persistence.repository.PublicationRepository;
import com.example.linkedinagent.application.ports.out.LinkedInAuthorizationPort;
import com.example.linkedinagent.application.ports.out.LinkedInPublishingPort;
import com.example.linkedinagent.application.ports.out.TokenCipherPort;
import com.example.linkedinagent.domain.publishing.LinkedInPublicationCommand;
import com.example.linkedinagent.domain.publishing.PublicationResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class DraftWorkflowService {

    private final ContentDraftRepository draftRepository;
    private final PublicationRepository publicationRepository;
    private final LinkedInAuthorizationPort authorizationPort;
    private final TokenCipherPort tokenCipher;
    private final LinkedInPublishingPort publishingPort;
    private final Clock clock;

    public DraftWorkflowService(
            ContentDraftRepository draftRepository,
            PublicationRepository publicationRepository,
            LinkedInAuthorizationPort authorizationPort,
            TokenCipherPort tokenCipher,
            LinkedInPublishingPort publishingPort,
            Clock clock) {
        this.draftRepository = draftRepository;
        this.publicationRepository = publicationRepository;
        this.authorizationPort = authorizationPort;
        this.tokenCipher = tokenCipher;
        this.publishingPort = publishingPort;
        this.clock = clock;
    }

    @Transactional
    public ContentDraftEntity createDraft(UUID appUserId, String text, String mediaCategory,
                                           String originalUrl, String title) {
        if (text == null || text.isBlank()) {
            throw error("INVALID_DRAFT", "Draft text is required.", HttpStatus.BAD_REQUEST);
        }
        if (mediaCategory != null && !mediaCategory.isBlank() && !"NONE".equals(mediaCategory)) {
            throw error("DRAFT_MEDIA_UNSUPPORTED", "Only text drafts are supported in this release.", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (originalUrl != null && !originalUrl.isBlank()) {
            throw error("DRAFT_MEDIA_UNSUPPORTED", "Article content is not supported in this release.", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        Instant now = clock.instant();
        ContentDraftEntity draft = new ContentDraftEntity();
        draft.setId(UUID.randomUUID());
        draft.setAppUser(referenceUser(appUserId));
        draft.setText(text.trim());
        draft.setMediaCategory("NONE");
        draft.setOriginalUrl(null);
        draft.setTitle(title == null || title.isBlank() ? null : title.trim());
        draft.setStatus("DRAFT");
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);
        return draftRepository.saveAndFlush(draft);
    }

    @Transactional
    public ContentDraftEntity approve(UUID appUserId, UUID draftId) {
        ContentDraftEntity draft = ownedDraft(appUserId, draftId);
        if (!"DRAFT".equals(draft.getStatus())) {
            throw error("DRAFT_INVALID_STATE", "Only a DRAFT can be approved.", HttpStatus.CONFLICT);
        }
        Instant now = clock.instant();
        draft.setStatus("APPROVED");
        draft.setApprovedAt(now);
        draft.setUpdatedAt(now);
        return draftRepository.saveAndFlush(draft);
    }

    @Transactional(noRollbackFor = LinkedInPublishingException.class)
    public PublicationEntity publish(UUID appUserId, UUID draftId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw error("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required.", HttpStatus.BAD_REQUEST);
        }
        ContentDraftEntity draft = ownedDraft(appUserId, draftId);
        String fingerprint = fingerprint(appUserId, draft, idempotencyKey);
        var existing = publicationRepository.findByAppUser_IdAndRequestFingerprint(appUserId, fingerprint);
        if (existing.isPresent()) {
            PublicationEntity publication = existing.get();
            if ("PUBLISHED".equals(publication.getStatus())) {
                return publication;
            }
            throw error("PUBLICATION_ALREADY_PROCESSED", "This idempotent publication request was already processed.", HttpStatus.CONFLICT);
        }
        if (!"APPROVED".equals(draft.getStatus())) {
            throw error("DRAFT_NOT_APPROVED", "Only an APPROVED draft can be published.", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        var credentials = authorizationPort.findCredentials(appUserId)
                .filter(value -> "ACTIVE".equals(value.status()))
                .filter(value -> value.expiresAt() == null || value.expiresAt().isAfter(clock.instant()))
                .orElseThrow(() -> error("LINKEDIN_CONNECTION_REQUIRED", "An active LinkedIn connection is required.", HttpStatus.UNPROCESSABLE_ENTITY));
        String accessToken = tokenCipher.decrypt(credentials.encryptedAccessToken());

        Instant now = clock.instant();
        PublicationEntity publication = new PublicationEntity();
        publication.setId(UUID.randomUUID());
        publication.setAppUser(referenceUser(appUserId));
        publication.setDraft(draft);
        publication.setRequestFingerprint(fingerprint);
        publication.setStatus("PUBLISHING");
        publication.setCreatedAt(now);
        publicationRepository.saveAndFlush(publication);

        try {
            PublicationResult result = publishingPort.publish(accessToken,
                    new LinkedInPublicationCommand(credentials.memberSubject(), draft.getText(),
                            LinkedInPublicationCommand.Visibility.PUBLIC));
            publication.setExternalPostId(result.externalPostId());
            publication.setStatus("PUBLISHED");
            publication.setPublishedAt(clock.instant());
            draft.setStatus("PUBLISHED");
            draft.setUpdatedAt(clock.instant());
            draftRepository.save(draft);
            return publicationRepository.saveAndFlush(publication);
        } catch (LinkedInPublishingException exception) {
            publication.setStatus("FAILED");
            publication.setFailureCode(exception.getFailureCode());
            publicationRepository.saveAndFlush(publication);
            throw exception;
        }
    }

    private ContentDraftEntity ownedDraft(UUID appUserId, UUID draftId) {
        return draftRepository.findByIdAndAppUser_Id(draftId, appUserId)
                .orElseThrow(() -> error("DRAFT_NOT_FOUND", "Draft was not found.", HttpStatus.NOT_FOUND));
    }

    private AppUserEntity referenceUser(UUID appUserId) {
        AppUserEntity user = new AppUserEntity();
        user.setId(appUserId);
        return user;
    }

    private String fingerprint(UUID appUserId, ContentDraftEntity draft, String idempotencyKey) {
        try {
            String input = appUserId + "|" + draft.getId() + "|" + draft.getApprovedAt() + "|" + idempotencyKey.trim();
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private DraftWorkflowException error(String code, String message, HttpStatus status) {
        return new DraftWorkflowException(code, message, status);
    }
}
