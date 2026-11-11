package com.example.linkedinagent.domain;

import com.example.linkedinagent.exception.DraftWorkflowException;
import com.example.linkedinagent.exception.LinkedInPublishingException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainModelTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    @Test
    void contentDraftLifecycleEnforcesTextOnlyInvariantsAndStateTransitions() {
        AppUser owner = AppUser.reference(UUID.randomUUID());

        ContentDraft draft = ContentDraft.createTextDraft(
                owner, "  Hello LinkedIn  ", null, "   ", "  Draft Title  ", NOW);
        assertThat(draft.getId()).isNotNull();
        assertThat(draft.getText()).isEqualTo("Hello LinkedIn");
        assertThat(draft.getMediaCategory()).isEqualTo(ContentDraft.MEDIA_NONE);
        assertThat(draft.getOriginalUrl()).isNull();
        assertThat(draft.getTitle()).isEqualTo("Draft Title");
        assertThat(draft.getStatus()).isEqualTo(ContentDraft.STATUS_DRAFT);

        assertThatThrownBy(draft::ensureApprovedForPublishing)
                .isInstanceOfSatisfying(DraftWorkflowException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("DRAFT_NOT_APPROVED");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });

        Instant editedAt = NOW.plusSeconds(10);
        draft.applyUpdate("  Updated body  ", "NONE", "", "   ", editedAt);
        assertThat(draft.getText()).isEqualTo("Updated body");
        assertThat(draft.getTitle()).isNull();
        assertThat(draft.getUpdatedAt()).isEqualTo(editedAt);

        Instant approvedAt = NOW.plusSeconds(20);
        draft.approve(approvedAt);
        assertThat(draft.getStatus()).isEqualTo(ContentDraft.STATUS_APPROVED);
        assertThat(draft.getApprovedAt()).isEqualTo(approvedAt);
        draft.ensureApprovedForPublishing();

        assertThatThrownBy(() -> draft.applyUpdate("Cannot edit", null, null, null, approvedAt.plusSeconds(5)))
                .isInstanceOfSatisfying(DraftWorkflowException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("DRAFT_INVALID_STATE");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        assertThatThrownBy(() -> draft.approve(approvedAt.plusSeconds(5)))
                .isInstanceOfSatisfying(DraftWorkflowException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("DRAFT_INVALID_STATE");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        Instant publishedAt = NOW.plusSeconds(30);
        draft.markPublished(publishedAt);
        assertThat(draft.getStatus()).isEqualTo(ContentDraft.STATUS_PUBLISHED);
        assertThat(draft.getUpdatedAt()).isEqualTo(publishedAt);
    }

    @Test
    void contentDraftRejectsBlankTextEmptyUpdatesAndUnsupportedMediaOrArticleUrls() {
        AppUser owner = AppUser.reference(UUID.randomUUID());

        assertThatThrownBy(() -> ContentDraft.createTextDraft(owner, "   ", "NONE", null, null, NOW))
                .isInstanceOfSatisfying(DraftWorkflowException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVALID_DRAFT");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });

        assertThatThrownBy(() -> ContentDraft.createTextDraft(owner, "Valid", "IMAGE", null, null, NOW))
                .isInstanceOfSatisfying(DraftWorkflowException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("DRAFT_MEDIA_UNSUPPORTED");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });

        assertThatThrownBy(() -> ContentDraft.createTextDraft(owner, "Valid", "NONE", "https://example.com", null, NOW))
                .isInstanceOfSatisfying(DraftWorkflowException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("DRAFT_MEDIA_UNSUPPORTED");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });

        ContentDraft draft = ContentDraft.createTextDraft(owner, "Valid", "NONE", null, null, NOW);
        assertThatThrownBy(() -> draft.applyUpdate(null, null, null, null, NOW))
                .isInstanceOfSatisfying(DraftWorkflowException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("INVALID_DRAFT_UPDATE"));
        assertThatThrownBy(() -> draft.applyUpdate("   ", null, null, null, NOW))
                .isInstanceOfSatisfying(DraftWorkflowException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("INVALID_DRAFT"));
        assertThatThrownBy(() -> draft.applyUpdate(null, "VIDEO", null, null, NOW))
                .isInstanceOfSatisfying(DraftWorkflowException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("DRAFT_MEDIA_UNSUPPORTED"));
        assertThatThrownBy(() -> draft.applyUpdate(null, null, "https://example.com", null, NOW))
                .isInstanceOfSatisfying(DraftWorkflowException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("DRAFT_MEDIA_UNSUPPORTED"));
    }

    @Test
    void publicationTracksReservationCompletionAndDeterministicVsAmbiguousFailures() {
        AppUser owner = AppUser.reference(UUID.randomUUID());
        ContentDraft draft = ContentDraft.createTextDraft(owner, "Post body", "NONE", null, null, NOW);

        Publication publication = Publication.reserve(owner, draft, "fp-1", NOW);
        assertThat(publication.getStatus()).isEqualTo(Publication.STATUS_PUBLISHING);
        assertThat(publication.isPublished()).isFalse();

        publication.markFailed(new LinkedInPublishingException("LINKEDIN_RATE_LIMITED", "rate limited", false));
        assertThat(publication.getStatus()).isEqualTo(Publication.STATUS_FAILED);
        assertThat(publication.getFailureCode()).isEqualTo("LINKEDIN_RATE_LIMITED");

        publication.markFailed(new LinkedInPublishingException("LINKEDIN_PROVIDER_UNAVAILABLE", "timeout", true));
        assertThat(publication.getStatus()).isEqualTo(Publication.STATUS_OUTCOME_UNKNOWN);
        assertThat(publication.getFailureCode()).isEqualTo(Publication.FAILURE_RESULT_UNKNOWN);

        Instant publishedAt = NOW.plusSeconds(15);
        publication.markPublished("urn:li:share:999", publishedAt);
        assertThat(publication.isPublished()).isTrue();
        assertThat(publication.getExternalPostId()).isEqualTo("urn:li:share:999");
        assertThat(publication.getPublishedAt()).isEqualTo(publishedAt);
    }

    @Test
    void linkedInAuthorizationAndOAuthTransactionEvaluateExpirationBoundaries() {
        AppUser user = AppUser.createNew("person@example.com", "Person", "hash", NOW);
        assertThat(user.isActive()).isTrue();

        LinkedInAuthorization authorization = LinkedInAuthorization.createForUser(user, NOW);
        authorization.refreshTokens("member-1", "enc-access", "enc-refresh", "openid profile", NOW.plusSeconds(60), NOW);
        assertThat(authorization.isUsableAt(NOW.plusSeconds(59))).isTrue();
        assertThat(authorization.toConnectionAt(NOW.plusSeconds(59)).status())
                .isEqualTo(LinkedInAuthorization.STATUS_ACTIVE);

        assertThat(authorization.isExpiredAt(NOW.plusSeconds(60))).isTrue();
        assertThat(authorization.isUsableAt(NOW.plusSeconds(60))).isFalse();
        assertThat(authorization.toConnectionAt(NOW.plusSeconds(60)).status())
                .isEqualTo(LinkedInAuthorization.STATUS_EXPIRED);

        OAuthTransaction transaction = OAuthTransaction.start(user, "state-hash", "openid", NOW, NOW.plusSeconds(600));
        assertThat(transaction.isUnexpiredAt(NOW.plusSeconds(599))).isTrue();
        assertThat(transaction.isUnexpiredAt(NOW.plusSeconds(600))).isFalse();
    }

    @Test
    void rateLimitBucketValidatesArgumentsAndFormatsStorageKey() {
        String validHash = "a".repeat(64);
        RateLimitBucket bucket = new RateLimitBucket("auth-login", "ip", validHash, 30, Duration.ofMinutes(15));
        assertThat(bucket.storageKey()).isEqualTo("auth-login:ip:" + validHash);

        assertThatThrownBy(() -> new RateLimitBucket("", "ip", validHash, 10, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitBucket("scope", "INVALID", validHash, 10, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitBucket("scope", "ip", "not-a-sha256-hex", 10, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitBucket("scope", "ip", validHash, 0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitBucket("scope", "ip", validHash, 10, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
