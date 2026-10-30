package com.example.linkedinagent.service;

import com.example.linkedinagent.client.LinkedInPublishingClient;
import com.example.linkedinagent.domain.ContentDraft;
import com.example.linkedinagent.domain.LinkedInAuthorization;
import com.example.linkedinagent.domain.Publication;
import com.example.linkedinagent.exception.DraftWorkflowException;
import com.example.linkedinagent.repository.ContentDraftRepository;
import com.example.linkedinagent.repository.LinkedInAuthorizationRepository;
import com.example.linkedinagent.repository.PublicationRepository;
import com.example.linkedinagent.security.TokenCipher;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DraftWorkflowServiceTest {

    @Test
    void concurrentDuplicateFingerprintIsReturnedAsConflictBeforeCallingLinkedIn() {
        UUID userId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-21T12:00:00Z");
        ContentDraftRepository draftRepository = mock(ContentDraftRepository.class);
        PublicationRepository publicationRepository = mock(PublicationRepository.class);
        LinkedInAuthorizationRepository authorizationRepository = mock(LinkedInAuthorizationRepository.class);
        TokenCipher tokenCipher = mock(TokenCipher.class);
        LinkedInPublishingClient publishingClient = mock(LinkedInPublishingClient.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        ContentDraft draft = new ContentDraft();
        draft.setId(draftId);
        draft.setText("Approved text");
        draft.setStatus(ContentDraft.STATUS_APPROVED);
        draft.setApprovedAt(now.minusSeconds(30));
        when(draftRepository.findLockedByIdAndAppUser_Id(draftId, userId)).thenReturn(Optional.of(draft));
        when(publicationRepository.findByAppUser_IdAndRequestFingerprint(any(), anyString()))
                .thenReturn(Optional.empty());
        when(publicationRepository.existsByAppUser_IdAndDraft_IdAndStatus(
                userId, draftId, Publication.STATUS_OUTCOME_UNKNOWN))
                .thenReturn(false);

        LinkedInAuthorization authorization = new LinkedInAuthorization();
        authorization.setMemberSubject("member-subject");
        authorization.setEncryptedAccessToken("encrypted-token");
        authorization.setStatus(LinkedInAuthorization.STATUS_ACTIVE);
        authorization.setExpiresAt(now.plusSeconds(3600));
        when(authorizationRepository.findByAppUser_Id(userId)).thenReturn(Optional.of(authorization));
        when(tokenCipher.decrypt("encrypted-token")).thenReturn("access-token");
        when(publicationRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("unique fingerprint already exists"));

        DraftWorkflowService service = new DraftWorkflowService(
                draftRepository,
                publicationRepository,
                authorizationRepository,
                tokenCipher,
                publishingClient,
                Clock.fixed(now, ZoneOffset.UTC),
                transactionManager);

        assertThatThrownBy(() -> service.publish(userId, draftId, "same-key"))
                .isInstanceOfSatisfying(DraftWorkflowException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("PUBLICATION_ALREADY_PROCESSED");
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception).hasMessage("This idempotent publication request was already processed.");
                });
        verify(publishingClient, never()).publish(anyString(), any());
    }
}
