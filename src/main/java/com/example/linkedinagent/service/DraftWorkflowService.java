package com.example.linkedinagent.service;

import com.example.linkedinagent.client.LinkedInPublishingClient;
import com.example.linkedinagent.domain.AppUser;
import com.example.linkedinagent.domain.ContentDraft;
import com.example.linkedinagent.domain.LinkedInAuthorization;
import com.example.linkedinagent.domain.LinkedInPublicationCommand;
import com.example.linkedinagent.domain.Publication;
import com.example.linkedinagent.domain.PublicationResult;
import com.example.linkedinagent.exception.DraftWorkflowException;
import com.example.linkedinagent.exception.LinkedInPublishingException;
import com.example.linkedinagent.repository.ContentDraftRepository;
import com.example.linkedinagent.repository.LinkedInAuthorizationRepository;
import com.example.linkedinagent.repository.PublicationRepository;
import com.example.linkedinagent.security.TokenCipher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

@Service
public class DraftWorkflowService {

    private final ContentDraftRepository draftRepository;
    private final PublicationRepository publicationRepository;
    private final LinkedInAuthorizationRepository authorizationRepository;
    private final TokenCipher tokenCipher;
    private final LinkedInPublishingClient publishingClient;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public DraftWorkflowService(
            ContentDraftRepository draftRepository,
            PublicationRepository publicationRepository,
            LinkedInAuthorizationRepository authorizationRepository,
            TokenCipher tokenCipher,
            LinkedInPublishingClient publishingClient,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.draftRepository = draftRepository;
        this.publicationRepository = publicationRepository;
        this.authorizationRepository = authorizationRepository;
        this.tokenCipher = tokenCipher;
        this.publishingClient = publishingClient;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public ContentDraft createDraft(
            UUID appUserId,
            String text,
            String mediaCategory,
            String originalUrl,
            String title) {
        ContentDraft draft = ContentDraft.createTextDraft(
                AppUser.reference(appUserId),
                text,
                mediaCategory,
                originalUrl,
                title,
                clock.instant());
        return draftRepository.saveAndFlush(draft);
    }

    @Transactional
    public ContentDraft approve(UUID appUserId, UUID draftId) {
        ContentDraft draft = ownedDraft(appUserId, draftId);
        draft.approve(clock.instant());
        return draftRepository.saveAndFlush(draft);
    }

    @Transactional(readOnly = true)
    public Page<ContentDraft> list(UUID appUserId, Pageable pageable) {
        return draftRepository.findAllByAppUser_Id(appUserId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Publication> listPublications(UUID appUserId, Pageable pageable) {
        return publicationRepository.findAllByAppUser_Id(appUserId, pageable);
    }

    @Transactional
    public ContentDraft update(
            UUID appUserId,
            UUID draftId,
            String text,
            String mediaCategory,
            String originalUrl,
            String title) {
        ContentDraft draft = ownedDraft(appUserId, draftId);
        draft.applyUpdate(text, mediaCategory, originalUrl, title, clock.instant());
        return draftRepository.saveAndFlush(draft);
    }

    public Publication publish(UUID appUserId, UUID draftId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw error("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required.", HttpStatus.BAD_REQUEST);
        }
        PublishAttempt attempt = Objects.requireNonNull(
                transactionTemplate.execute(transaction -> reservePublication(appUserId, draftId, idempotencyKey)),
                "Publication reservation transaction returned no result.");
        if (attempt.replay()) {
            return attempt.publication();
        }

        PublicationResult result;
        try {
            result = publishingClient.publish(attempt.accessToken(), attempt.command());
        } catch (LinkedInPublishingException exception) {
            transactionTemplate.execute(transaction -> {
                recordPublicationFailure(attempt.publication().getId(), exception);
                return null;
            });
            throw exception;
        }
        return Objects.requireNonNull(
                transactionTemplate.execute(transaction ->
                        completePublication(appUserId, draftId, attempt.publication().getId(), result)),
                "Publication completion transaction returned no result.");
    }

    private PublishAttempt reservePublication(UUID appUserId, UUID draftId, String idempotencyKey) {
        ContentDraft draft = draftRepository.findLockedByIdAndAppUser_Id(draftId, appUserId)
                .orElseThrow(() -> error("DRAFT_NOT_FOUND", "Draft was not found.", HttpStatus.NOT_FOUND));
        String fingerprint = fingerprint(appUserId, draft, idempotencyKey);
        var existing = publicationRepository.findByAppUser_IdAndRequestFingerprint(appUserId, fingerprint);
        if (existing.isPresent()) {
            Publication publication = existing.get();
            if (publication.isPublished()) {
                return new PublishAttempt(publication, null, null, true);
            }
            throw error("PUBLICATION_ALREADY_PROCESSED",
                    "This idempotent publication request was already processed.", HttpStatus.CONFLICT);
        }
        if (publicationRepository.existsByAppUser_IdAndDraft_IdAndStatus(
                appUserId, draftId, Publication.STATUS_OUTCOME_UNKNOWN)
                || publicationRepository.existsByAppUser_IdAndDraft_IdAndStatus(
                appUserId, draftId, Publication.STATUS_PUBLISHING)) {
            throw error("PUBLICATION_OUTCOME_UNKNOWN",
                    "A previous publication attempt is unresolved; reconcile it before retrying.",
                    HttpStatus.CONFLICT);
        }
        draft.ensureApprovedForPublishing();

        Instant now = clock.instant();
        LinkedInAuthorization authorization = authorizationRepository.findByAppUser_Id(appUserId)
                .filter(value -> value.isUsableAt(now))
                .orElseThrow(() -> error("LINKEDIN_CONNECTION_REQUIRED",
                        "An active LinkedIn connection is required.", HttpStatus.UNPROCESSABLE_ENTITY));
        String accessToken = tokenCipher.decrypt(authorization.getEncryptedAccessToken());

        Publication publication = Publication.reserve(AppUser.reference(appUserId), draft, fingerprint, now);
        try {
            publicationRepository.saveAndFlush(publication);
        } catch (DataIntegrityViolationException exception) {
            throw error("PUBLICATION_ALREADY_PROCESSED",
                    "This idempotent publication request was already processed.", HttpStatus.CONFLICT);
        }
        LinkedInPublicationCommand command = new LinkedInPublicationCommand(
                authorization.getMemberSubject(),
                draft.getText(),
                LinkedInPublicationCommand.Visibility.PUBLIC);
        return new PublishAttempt(publication, accessToken, command, false);
    }

    private Publication completePublication(
            UUID appUserId,
            UUID draftId,
            UUID publicationId,
            PublicationResult result) {
        Publication publication = publicationRepository.findById(publicationId)
                .orElseThrow(() -> new IllegalStateException("Reserved publication was not found."));
        ContentDraft draft = draftRepository.findLockedByIdAndAppUser_Id(draftId, appUserId)
                .orElseThrow(() -> new IllegalStateException("Reserved draft was not found."));
        Instant now = clock.instant();
        publication.markPublished(result.externalPostId(), now);
        draft.markPublished(now);
        draftRepository.save(draft);
        return publicationRepository.saveAndFlush(publication);
    }

    private void recordPublicationFailure(UUID publicationId, LinkedInPublishingException exception) {
        Publication publication = publicationRepository.findById(publicationId)
                .orElseThrow(() -> new IllegalStateException("Reserved publication was not found."));
        publication.markFailed(exception);
        publicationRepository.saveAndFlush(publication);
    }

    private ContentDraft ownedDraft(UUID appUserId, UUID draftId) {
        return draftRepository.findByIdAndAppUser_Id(draftId, appUserId)
                .orElseThrow(() -> error("DRAFT_NOT_FOUND", "Draft was not found.", HttpStatus.NOT_FOUND));
    }

    private String fingerprint(UUID appUserId, ContentDraft draft, String idempotencyKey) {
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

    private record PublishAttempt(
            Publication publication,
            String accessToken,
            LinkedInPublicationCommand command,
            boolean replay) {
    }
}
