package com.example.linkedinagent.domain;

import com.example.linkedinagent.exception.DraftWorkflowException;
import com.example.linkedinagent.exception.LinkedInPublishingException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainModelTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    @Test
    void contentDraftAndPublicationEnforceStateAndMediaBoundaries() {
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

        ContentDraft draft = ContentDraft.createTextDraft(owner, "Hello LinkedIn", null, "   ", "Title", NOW);
        assertThatThrownBy(draft::ensureApprovedForPublishing)
                .isInstanceOfSatisfying(DraftWorkflowException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("DRAFT_NOT_APPROVED"));
        assertThatThrownBy(() -> draft.applyUpdate(null, null, null, null, NOW))
                .isInstanceOfSatisfying(DraftWorkflowException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("INVALID_DRAFT_UPDATE"));

        draft.approve(NOW.plusSeconds(10));
        assertThatThrownBy(() -> draft.applyUpdate("Cannot edit", null, null, null, NOW.plusSeconds(15)))
                .isInstanceOfSatisfying(DraftWorkflowException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("DRAFT_INVALID_STATE"));
        assertThatThrownBy(() -> draft.approve(NOW.plusSeconds(15)))
                .isInstanceOfSatisfying(DraftWorkflowException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("DRAFT_INVALID_STATE"));

        Publication publication = Publication.reserve(owner, draft, "fp-1", NOW);
        publication.markFailed(new LinkedInPublishingException("LINKEDIN_RATE_LIMITED", "rate limited", false));
        assertThat(publication.getStatus()).isEqualTo(Publication.STATUS_FAILED);
        assertThat(publication.getFailureCode()).isEqualTo("LINKEDIN_RATE_LIMITED");

        publication.markFailed(new LinkedInPublishingException("LINKEDIN_PROVIDER_UNAVAILABLE", "timeout", true));
        assertThat(publication.getStatus()).isEqualTo(Publication.STATUS_OUTCOME_UNKNOWN);
        assertThat(publication.getFailureCode()).isEqualTo(Publication.FAILURE_RESULT_UNKNOWN);
    }

    @Test
    void linkedInAuthorizationAndOAuthTransactionEvaluateExpirationBoundaries() {
        AppUser user = AppUser.createNew("person@example.com", "Person", "hash", NOW);

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
    void auditEventRejectsSecretMarkersInMetadata() {
        AppUser user = AppUser.reference(UUID.randomUUID());

        assertThatThrownBy(() -> AuditEvent.record(user, " ", "RES", "1", "OK", NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditEvent.record(user, "EVT", "RES", "1", " ", NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditEvent.record(user, "EVT", "RES", "1", "OK", NOW, "token=v1.abcd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditEvent.record(user, "EVT", "RES", "1", "OK", NOW, "Bearer secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditEvent.record(user, "EVT", "RES", "1", "OK", NOW, "access_token=xyz"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditEvent.record(user, "EVT", "RES", "1", "OK", NOW, "password=123"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
