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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class DraftWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(DraftWorkflowService.class);
    private static final Duration STUCK_PUBLISHING_TIMEOUT = Duration.ofMinutes(2);

    private final ContentDraftRepository draftRepository;
    private final PublicationRepository publicationRepository;
    private final LinkedInAuthorizationRepository authorizationRepository;
    private final TokenCipher tokenCipher;
    private final LinkedInPublishingClient publishingClient;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final AuditService auditService;

    public DraftWorkflowService(
            ContentDraftRepository draftRepository,
            PublicationRepository publicationRepository,
            LinkedInAuthorizationRepository authorizationRepository,
            TokenCipher tokenCipher,
            LinkedInPublishingClient publishingClient,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this(
                draftRepository,
                publicationRepository,
                authorizationRepository,
                tokenCipher,
                publishingClient,
                clock,
                transactionManager,
                null);
    }

    @Autowired
    public DraftWorkflowService(
            ContentDraftRepository draftRepository,
            PublicationRepository publicationRepository,
            LinkedInAuthorizationRepository authorizationRepository,
            TokenCipher tokenCipher,
            LinkedInPublishingClient publishingClient,
            Clock clock,
            PlatformTransactionManager transactionManager,
            AuditService auditService) {
        this.draftRepository = draftRepository;
        this.publicationRepository = publicationRepository;
        this.authorizationRepository = authorizationRepository;
        this.tokenCipher = tokenCipher;
        this.publishingClient = publishingClient;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.auditService = auditService;
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
        ContentDraft saved = draftRepository.saveAndFlush(draft);
        log.info("Created text draft userId={} draftId={} status={}",
                appUserId, saved.getId(), saved.getStatus());
        return saved;
    }

    @Transactional
    public ContentDraft approve(UUID appUserId, UUID draftId) {
        return approve(appUserId, draftId, null);
    }

    @Transactional
    public ContentDraft approve(UUID appUserId, UUID draftId, Instant scheduledAt) {
        Instant now = clock.instant();
        ContentDraft draft = ownedDraft(appUserId, draftId);
        draft.approve(now, scheduledAt);
        if (scheduledAt != null) {
            ensureLinkedInUsableForSchedule(appUserId, now, scheduledAt);
        }
        ContentDraft approved = draftRepository.saveAndFlush(draft);
        if (auditService != null) {
            if (scheduledAt != null) {
                auditService.recordForUser(
                        appUserId,
                        "DRAFT_SCHEDULED",
                        "CONTENT_DRAFT",
                        approved.getId().toString(),
                        ContentDraft.STATUS_SCHEDULED,
                        "scheduledAt=" + scheduledAt);
            } else {
                auditService.recordForUser(
                        appUserId,
                        "DRAFT_APPROVED",
                        "CONTENT_DRAFT",
                        approved.getId().toString(),
                        ContentDraft.STATUS_APPROVED,
                        null);
            }
        }
        log.info("Approved draft userId={} draftId={} status={} approvedAt={} scheduledAt={}",
                appUserId, approved.getId(), approved.getStatus(), approved.getApprovedAt(), approved.getScheduledAt());
        return approved;
    }

    @Transactional
    public ContentDraft schedule(UUID appUserId, UUID draftId, Instant scheduledAt) {
        Instant now = clock.instant();
        ContentDraft draft = draftRepository.findLockedByIdAndAppUser_Id(draftId, appUserId)
                .orElseThrow(() -> error("DRAFT_NOT_FOUND", "Draft was not found.", HttpStatus.NOT_FOUND));
        ensureNoBlockingPublication(appUserId, draftId);
        draft.schedule(scheduledAt, now);
        ensureLinkedInUsableForSchedule(appUserId, now, scheduledAt);
        ContentDraft saved = draftRepository.saveAndFlush(draft);
        if (auditService != null) {
            auditService.recordForUser(
                    appUserId,
                    "DRAFT_SCHEDULED",
                    "CONTENT_DRAFT",
                    saved.getId().toString(),
                    ContentDraft.STATUS_SCHEDULED,
                    "scheduledAt=" + scheduledAt);
        }
        log.info("Scheduled draft userId={} draftId={} scheduledAt={}", appUserId, saved.getId(), scheduledAt);
        return saved;
    }

    @Transactional
    public ContentDraft cancelSchedule(UUID appUserId, UUID draftId) {
        Instant now = clock.instant();
        ContentDraft draft = draftRepository.findLockedByIdAndAppUser_Id(draftId, appUserId)
                .orElseThrow(() -> error("DRAFT_NOT_FOUND", "Draft was not found.", HttpStatus.NOT_FOUND));
        ensureNoBlockingPublication(appUserId, draftId);
        draft.cancelSchedule(now);
        ContentDraft saved = draftRepository.saveAndFlush(draft);
        if (auditService != null) {
            auditService.recordForUser(
                    appUserId,
                    "DRAFT_SCHEDULE_CANCELLED",
                    "CONTENT_DRAFT",
                    saved.getId().toString(),
                    ContentDraft.STATUS_APPROVED,
                    null);
        }
        log.info("Cancelled draft schedule userId={} draftId={}", appUserId, saved.getId());
        return saved;
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

    public Publication publish(UUID appUserId, UUID draftId, String testIdempotencyKey) {
        if (testIdempotencyKey == null || testIdempotencyKey.isBlank() || testIdempotencyKey.length() > 200) {
            throw error("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required.", HttpStatus.BAD_REQUEST);
        }
        Instant cutoff = clock.instant().minus(STUCK_PUBLISHING_TIMEOUT);
        transactionTemplate.executeWithoutResult(transaction ->
                publicationRepository.markTimedOutPublishingForUserAsOutcomeUnknown(appUserId, cutoff));
        PublishAttempt attempt = Objects.requireNonNull(
                transactionTemplate.execute(transaction -> reservePublication(appUserId, draftId, testIdempotencyKey)),
                "Publication reservation transaction returned no result.");
        if (attempt.replay()) {
            log.info("Replayed completed publication userId={} draftId={} publicationId={} status={}",
                    appUserId, draftId, attempt.publication().getId(), attempt.publication().getStatus());
            return attempt.publication();
        }

        long startedNanos = System.nanoTime();
        PublicationResult result;
        try {
            result = publishingClient.publish(attempt.accessToken(), attempt.command());
        } catch (LinkedInPublishingException exception) {
            long latencyMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
            UUID publicationId = attempt.publication().getId();
            transactionTemplate.execute(transaction -> {
                recordPublicationFailure(appUserId, draftId, publicationId, exception, latencyMs, false);
                return null;
            });
            log.warn("LinkedIn publication failed userId={} draftId={} publicationId={} failureCode={} outcomeUnknown={} latencyMs={}",
                    appUserId, draftId, publicationId, exception.getFailureCode(), exception.isOutcomeUnknown(), latencyMs);
            throw exception;
        }
        long latencyMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        try {
            Publication completed = Objects.requireNonNull(
                    transactionTemplate.execute(transaction ->
                            completePublication(appUserId, draftId, attempt.publication().getId(), result, latencyMs)),
                    "Publication completion transaction returned no result.");
            log.info("LinkedIn publication completed userId={} draftId={} publicationId={} status={} latencyMs={}",
                    appUserId, draftId, completed.getId(), completed.getStatus(), latencyMs);
            return completed;
        } catch (RuntimeException exception) {
            UUID publicationId = attempt.publication().getId();
            LinkedInPublishingException unknownOutcome = new LinkedInPublishingException(
                    Publication.FAILURE_RESULT_UNKNOWN,
                    "LinkedIn accepted the post, but local completion failed.",
                    true);
            transactionTemplate.execute(transaction -> {
                recordPublicationFailure(appUserId, draftId, publicationId, unknownOutcome, latencyMs, false);
                return null;
            });
            log.error("Local publication completion failed after LinkedIn accepted post userId={} draftId={} publicationId={}",
                    appUserId, draftId, publicationId, exception);
            throw unknownOutcome;
        }
    }

    public int processDueScheduledDrafts(Duration graceWindow, int batchSize) {
        Objects.requireNonNull(graceWindow, "graceWindow is required.");
        if (graceWindow.isNegative()) {
            throw new IllegalArgumentException("graceWindow must not be negative.");
        }
        Instant now = clock.instant();
        List<UUID> candidateIds = transactionTemplate.execute(transaction ->
                draftRepository.findScheduledDraftIdsUpTo(
                        ContentDraft.STATUS_SCHEDULED,
                        now.plusSeconds(1),
                        PageRequest.of(0, Math.max(1, batchSize))));
        if (candidateIds == null || candidateIds.isEmpty()) {
            return 0;
        }

        int processedCount = 0;
        for (UUID draftId : candidateIds) {
            try {
                if (processSingleScheduledDraft(draftId, graceWindow)) {
                    processedCount++;
                }
            } catch (RuntimeException exception) {
                log.error("Unexpected error while processing scheduled draft draftId={}", draftId, exception);
            }
        }
        return processedCount;
    }

    private boolean processSingleScheduledDraft(UUID draftId, Duration graceWindow) {
        ScheduledPublishAttempt attempt = transactionTemplate.execute(transaction ->
                reserveScheduledPublication(draftId, graceWindow));
        if (attempt == null || !attempt.readyToPublish()) {
            return attempt != null && attempt.stateChanged();
        }

        UUID appUserId = attempt.appUserId();
        UUID publicationId = attempt.publicationId();
        long startedNanos = System.nanoTime();
        PublicationResult result;
        try {
            result = publishingClient.publish(attempt.accessToken(), attempt.command());
        } catch (LinkedInPublishingException exception) {
            long latencyMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
            transactionTemplate.executeWithoutResult(transaction ->
                    recordPublicationFailure(appUserId, draftId, publicationId, exception, latencyMs, true));
            log.warn("Scheduled LinkedIn publication failed userId={} draftId={} publicationId={} failureCode={} outcomeUnknown={} latencyMs={}",
                    appUserId, draftId, publicationId, exception.getFailureCode(), exception.isOutcomeUnknown(), latencyMs);
            return true;
        } catch (RuntimeException exception) {
            long latencyMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
            LinkedInPublishingException unknownOutcome = new LinkedInPublishingException(
                    Publication.FAILURE_RESULT_UNKNOWN,
                    "Scheduled publication encountered an unexpected error during external call.",
                    true);
            transactionTemplate.executeWithoutResult(transaction ->
                    recordPublicationFailure(appUserId, draftId, publicationId, unknownOutcome, latencyMs, true));
            log.error("Scheduled LinkedIn publication encountered unexpected error userId={} draftId={} publicationId={}",
                    appUserId, draftId, publicationId, exception);
            return true;
        }

        long latencyMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        try {
            Publication completed = Objects.requireNonNull(
                    transactionTemplate.execute(transaction ->
                            completePublication(appUserId, draftId, publicationId, result, latencyMs)),
                    "Scheduled publication completion transaction returned no result.");
            log.info("Scheduled LinkedIn publication completed userId={} draftId={} publicationId={} status={} latencyMs={}",
                    appUserId, draftId, completed.getId(), completed.getStatus(), latencyMs);
            return true;
        } catch (RuntimeException exception) {
            LinkedInPublishingException unknownOutcome = new LinkedInPublishingException(
                    Publication.FAILURE_RESULT_UNKNOWN,
                    "LinkedIn accepted the scheduled post, but local completion failed.",
                    true);
            transactionTemplate.executeWithoutResult(transaction ->
                    recordPublicationFailure(appUserId, draftId, publicationId, unknownOutcome, latencyMs, true));
            log.error("Local completion failed after LinkedIn accepted scheduled post userId={} draftId={} publicationId={}",
                    appUserId, draftId, publicationId, exception);
            return true;
        }
    }

    private ScheduledPublishAttempt reserveScheduledPublication(UUID draftId, Duration graceWindow) {
        Optional<ContentDraft> lockedOpt = draftRepository.findLockedById(draftId);
        if (lockedOpt.isEmpty()) {
            return ScheduledPublishAttempt.skipped();
        }
        ContentDraft draft = lockedOpt.get();
        Instant now = clock.instant();
        if (!draft.isDueAt(now)) {
            return ScheduledPublishAttempt.skipped();
        }

        UUID appUserId = draft.getAppUser().getId();
        publicationRepository.markTimedOutPublishingForUserAsOutcomeUnknown(
                appUserId, now.minus(STUCK_PUBLISHING_TIMEOUT));

        if (publicationRepository.existsByAppUser_IdAndDraft_IdAndStatus(
                appUserId, draftId, Publication.STATUS_OUTCOME_UNKNOWN)) {
            draft.revertScheduleAfterFailure(now);
            draftRepository.saveAndFlush(draft);
            return ScheduledPublishAttempt.changedWithoutExternalCall();
        }
        if (publicationRepository.existsByAppUser_IdAndDraft_IdAndStatus(
                appUserId, draftId, Publication.STATUS_PUBLISHING)) {
            return ScheduledPublishAttempt.skipped();
        }

        Instant scheduledAt = draft.getScheduledAt();
        if (now.isAfter(scheduledAt.plus(graceWindow))) {
            draft.markScheduleExpired(now);
            draftRepository.saveAndFlush(draft);
            if (auditService != null) {
                auditService.recordForUser(
                        appUserId,
                        "DRAFT_SCHEDULE_EXPIRED",
                        "CONTENT_DRAFT",
                        draftId.toString(),
                        ContentDraft.STATUS_SCHEDULE_EXPIRED,
                        "scheduledAt=" + scheduledAt);
            }
            log.info("Scheduled draft missed grace window userId={} draftId={} scheduledAt={} now={}",
                    appUserId, draftId, scheduledAt, now);
            return ScheduledPublishAttempt.changedWithoutExternalCall();
        }

        String fingerprint = fingerprint(appUserId, "scheduled:" + draftId + ":" + scheduledAt);
        var existing = publicationRepository.findByAppUser_IdAndRequestFingerprint(appUserId, fingerprint);
        if (existing.isPresent()) {
            if (existing.get().isPublished()) {
                draft.markPublished(now);
            } else {
                draft.revertScheduleAfterFailure(now);
            }
            draftRepository.saveAndFlush(draft);
            return ScheduledPublishAttempt.changedWithoutExternalCall();
        }

        Optional<LinkedInAuthorization> authOpt = authorizationRepository.findByAppUser_Id(appUserId)
                .filter(value -> value.isUsableAt(now));
        if (authOpt.isEmpty()) {
            Publication failedPublication = Publication.reserve(AppUser.reference(appUserId), draft, fingerprint, now);
            failedPublication.markFailed(new LinkedInPublishingException(
                    "LINKEDIN_CONNECTION_REQUIRED",
                    "An active LinkedIn connection is required.",
                    false));
            Publication savedFailure = publicationRepository.saveAndFlush(failedPublication);
            draft.revertScheduleAfterFailure(now);
            draftRepository.saveAndFlush(draft);
            if (auditService != null) {
                auditService.recordForUser(
                        appUserId,
                        "PUBLICATION_FAILED",
                        "PUBLICATION",
                        savedFailure.getId().toString(),
                        savedFailure.getStatus(),
                        "draftId=" + draftId + ";failureCode=LINKEDIN_CONNECTION_REQUIRED;scheduled=true");
            }
            log.warn("Scheduled publication aborted due to missing/expired LinkedIn authorization userId={} draftId={}",
                    appUserId, draftId);
            return ScheduledPublishAttempt.changedWithoutExternalCall();
        }

        LinkedInAuthorization authorization = authOpt.get();
        String accessToken = tokenCipher.decrypt(
                authorization.getEncryptedAccessToken(), appUserId.toString());
        Publication publication = Publication.reserve(AppUser.reference(appUserId), draft, fingerprint, now);
        try {
            publicationRepository.saveAndFlush(publication);
        } catch (DataIntegrityViolationException exception) {
            return ScheduledPublishAttempt.skipped();
        }

        LinkedInPublicationCommand command = new LinkedInPublicationCommand(
                authorization.getMemberSubject(),
                draft.getText(),
                LinkedInPublicationCommand.Visibility.PUBLIC);
        return ScheduledPublishAttempt.ready(appUserId, publication.getId(), accessToken, command);
    }

    @Transactional
    public int expireTimedOutPublishingReservations(Duration maxAge) {
        Instant cutoff = clock.instant().minus(maxAge);
        return publicationRepository.markTimedOutPublishingAsOutcomeUnknown(cutoff);
    }

    private void ensureNoBlockingPublication(UUID appUserId, UUID draftId) {
        if (publicationRepository.existsByAppUser_IdAndDraft_IdAndStatus(
                appUserId, draftId, Publication.STATUS_OUTCOME_UNKNOWN)) {
            throw error("PUBLICATION_OUTCOME_UNKNOWN",
                    "A previous publication attempt is unresolved; reconcile it before retrying.",
                    HttpStatus.CONFLICT);
        }
        if (publicationRepository.existsByAppUser_IdAndDraft_IdAndStatus(
                appUserId, draftId, Publication.STATUS_PUBLISHING)) {
            throw error("PUBLICATION_IN_PROGRESS",
                    "A publication attempt for this draft is already in progress; reconcile it if interrupted.",
                    HttpStatus.CONFLICT);
        }
    }

    private void ensureLinkedInUsableForSchedule(UUID appUserId, Instant now, Instant scheduledAt) {
        LinkedInAuthorization authorization = authorizationRepository.findByAppUser_Id(appUserId)
                .filter(value -> value.isUsableAt(now))
                .orElseThrow(() -> error("LINKEDIN_CONNECTION_REQUIRED",
                        "An active LinkedIn connection is required.", HttpStatus.UNPROCESSABLE_ENTITY));
        if (authorization.isExpiredAt(scheduledAt)) {
            throw error("LINKEDIN_TOKEN_EXPIRES_BEFORE_SCHEDULE",
                    "LinkedIn authorization expires before the scheduled publication time; reconnect LinkedIn first.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private PublishAttempt reservePublication(UUID appUserId, UUID draftId, String testIdempotencyKey) {
        Instant now = clock.instant();
        ContentDraft draft = draftRepository.findLockedByIdAndAppUser_Id(draftId, appUserId)
                .orElseThrow(() -> error("DRAFT_NOT_FOUND", "Draft was not found.", HttpStatus.NOT_FOUND));
        String fingerprint = fingerprint(appUserId, testIdempotencyKey);
        var existing = publicationRepository.findByAppUser_IdAndRequestFingerprint(appUserId, fingerprint);
        if (existing.isPresent()) {
            Publication publication = existing.get();
            if (!publication.isSameDraft(draftId)) {
                throw error("IDEMPOTENCY_KEY_CONFLICT",
                        "This Idempotency-Key was already used for a different publication operation.",
                        HttpStatus.CONFLICT);
            }
            if (publication.isPublished()) {
                return new PublishAttempt(publication, null, null, true);
            }
            throw error("PUBLICATION_ALREADY_PROCESSED",
                    "This idempotent publication request was already processed.", HttpStatus.CONFLICT);
        }
        ensureNoBlockingPublication(appUserId, draftId);
        draft.ensureApprovedForPublishing();

        LinkedInAuthorization authorization = authorizationRepository.findByAppUser_Id(appUserId)
                .filter(value -> value.isUsableAt(now))
                .orElseThrow(() -> error("LINKEDIN_CONNECTION_REQUIRED",
                        "An active LinkedIn connection is required.", HttpStatus.UNPROCESSABLE_ENTITY));
        String accessToken = tokenCipher.decrypt(
                authorization.getEncryptedAccessToken(), appUserId.toString());

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
            PublicationResult result,
            long latencyMs) {
        Publication publication = publicationRepository.findById(publicationId)
                .orElseThrow(() -> new IllegalStateException("Reserved publication was not found."));
        ContentDraft draft = draftRepository.findLockedByIdAndAppUser_Id(draftId, appUserId)
                .orElseThrow(() -> new IllegalStateException("Reserved draft was not found."));
        Instant now = clock.instant();
        publication.markPublished(result.externalPostId(), now);
        draft.markPublished(now);
        draftRepository.save(draft);
        Publication saved = publicationRepository.saveAndFlush(publication);
        if (auditService != null) {
            auditService.recordForUser(
                    appUserId,
                    "PUBLICATION_COMPLETED",
                    "PUBLICATION",
                    saved.getId().toString(),
                    Publication.STATUS_PUBLISHED,
                    "draftId=" + draftId + ";externalPostId=" + result.externalPostId() + ";latencyMs=" + latencyMs);
        }
        return saved;
    }

    private void recordPublicationFailure(
            UUID appUserId,
            UUID draftId,
            UUID publicationId,
            LinkedInPublishingException exception,
            long latencyMs,
            boolean revertScheduleOnDraft) {
        Publication publication = publicationRepository.findById(publicationId)
                .orElseThrow(() -> new IllegalStateException("Reserved publication was not found."));
        publication.markFailed(exception);
        if (revertScheduleOnDraft) {
            draftRepository.findLockedByIdAndAppUser_Id(draftId, appUserId).ifPresent(draft -> {
                if (ContentDraft.STATUS_SCHEDULED.equals(draft.getStatus())) {
                    draft.revertScheduleAfterFailure(clock.instant());
                    draftRepository.save(draft);
                }
            });
        }
        Publication saved = publicationRepository.saveAndFlush(publication);
        if (auditService != null) {
            auditService.recordForUser(
                    appUserId,
                    "PUBLICATION_FAILED",
                    "PUBLICATION",
                    saved.getId().toString(),
                    saved.getStatus(),
                    "draftId=" + draftId + ";failureCode=" + saved.getFailureCode() + ";latencyMs=" + latencyMs);
        }
    }

    private ContentDraft ownedDraft(UUID appUserId, UUID draftId) {
        return draftRepository.findByIdAndAppUser_Id(draftId, appUserId)
                .orElseThrow(() -> error("DRAFT_NOT_FOUND", "Draft was not found.", HttpStatus.NOT_FOUND));
    }

    private String fingerprint(UUID appUserId, String idempotencyKey) {
        try {
            String input = appUserId + "|" + idempotencyKey.trim();
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

    private record ScheduledPublishAttempt(
            UUID appUserId,
            UUID publicationId,
            String accessToken,
            LinkedInPublicationCommand command,
            boolean readyToPublish,
            boolean stateChanged) {

        static ScheduledPublishAttempt skipped() {
            return new ScheduledPublishAttempt(null, null, null, null, false, false);
        }

        static ScheduledPublishAttempt changedWithoutExternalCall() {
            return new ScheduledPublishAttempt(null, null, null, null, false, true);
        }

        static ScheduledPublishAttempt ready(
                UUID appUserId,
                UUID publicationId,
                String accessToken,
                LinkedInPublicationCommand command) {
            return new ScheduledPublishAttempt(appUserId, publicationId, accessToken, command, true, true);
        }
    }
}
