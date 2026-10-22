package com.example.linkedinagent.application.publishing;

import com.example.linkedinagent.adapter.out.persistence.entity.ContentDraftEntity;
import com.example.linkedinagent.adapter.out.persistence.repository.ContentDraftRepository;
import com.example.linkedinagent.adapter.out.persistence.repository.PublicationRepository;
import com.example.linkedinagent.application.ports.out.LinkedInAuthorizationPort;
import com.example.linkedinagent.application.ports.out.LinkedInPublishingPort;
import com.example.linkedinagent.application.ports.out.TokenCipherPort;
import com.example.linkedinagent.exception.DraftWorkflowException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

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
        LinkedInAuthorizationPort authorizationPort = mock(LinkedInAuthorizationPort.class);
        TokenCipherPort tokenCipher = mock(TokenCipherPort.class);
        LinkedInPublishingPort publishingPort = mock(LinkedInPublishingPort.class);

        ContentDraftEntity draft = new ContentDraftEntity();
        draft.setId(draftId);
        draft.setText("Approved text");
        draft.setStatus("APPROVED");
        draft.setApprovedAt(now.minusSeconds(30));
        when(draftRepository.findByIdAndAppUser_Id(draftId, userId)).thenReturn(Optional.of(draft));
        when(publicationRepository.findByAppUser_IdAndRequestFingerprint(any(), anyString()))
                .thenReturn(Optional.empty());
        when(authorizationPort.findCredentials(userId)).thenReturn(Optional.of(
                new LinkedInAuthorizationPort.LinkedInAuthorizationCredentials(
                        "member-subject", "encrypted-token", "ACTIVE", now.plusSeconds(3600))));
        when(tokenCipher.decrypt("encrypted-token")).thenReturn("access-token");
        when(publicationRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("unique fingerprint already exists"));

        DraftWorkflowService service = new DraftWorkflowService(
                draftRepository,
                publicationRepository,
                authorizationPort,
                tokenCipher,
                publishingPort,
                Clock.fixed(now, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.publish(userId, draftId, "same-key"))
                .isInstanceOfSatisfying(DraftWorkflowException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("PUBLICATION_ALREADY_PROCESSED");
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception).hasMessage("This idempotent publication request was already processed.");
                });
        verify(publishingPort, never()).publish(anyString(), any());
    }
}
