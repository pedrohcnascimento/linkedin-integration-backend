package com.example.linkedinagent.dto;

import com.example.linkedinagent.domain.AppUser;
import com.example.linkedinagent.domain.ContentDraft;
import com.example.linkedinagent.domain.LinkedInAuthorization;
import com.example.linkedinagent.domain.LinkedInConnection;
import com.example.linkedinagent.domain.Publication;
import com.example.linkedinagent.security.AppUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DtoMappingTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    @Test
    void authAndConnectionDtosMapDomainModelsWithoutExposingSecrets() {
        AppUser user = AppUser.createNew("person@example.com", "Person", "secret-bcrypt-hash", NOW);
        AppUserPrincipal principal = new AppUserPrincipal(user);

        AuthDtos.UserResponse fromEntity = AuthDtos.UserResponse.from(user);
        AuthDtos.UserResponse fromPrincipal = AuthDtos.UserResponse.from(principal);
        assertThat(fromEntity).isEqualTo(fromPrincipal);
        assertThat(fromEntity.email()).isEqualTo("person@example.com");
        assertThat(fromEntity.toString()).doesNotContain("secret-bcrypt-hash");

        LinkedInConnection connection = new LinkedInConnection(
                "member-1", "openid profile", LinkedInAuthorization.STATUS_ACTIVE, NOW.plusSeconds(3600), NOW);
        LinkedInOAuthDtos.ConnectionResponse active = LinkedInOAuthDtos.ConnectionResponse.from(connection);
        assertThat(active.connected()).isTrue();
        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThat(active.memberSubject()).isEqualTo("member-1");

        LinkedInOAuthDtos.ConnectionResponse disconnected = LinkedInOAuthDtos.ConnectionResponse.disconnected();
        assertThat(disconnected.connected()).isFalse();
        assertThat(disconnected.status()).isEqualTo("DISCONNECTED");
        assertThat(disconnected.memberSubject()).isNull();
    }

    @Test
    void draftAndPublicationPageDtosPreservePaginationMetadataAndHandleNullableDraftReference() {
        AppUser owner = AppUser.reference(UUID.randomUUID());
        ContentDraft draft = ContentDraft.createTextDraft(owner, "Hello", "NONE", null, "Title", NOW);
        var draftPage = DraftDtos.DraftPageResponse.from(
                new PageImpl<>(List.of(draft), PageRequest.of(0, 20), 1));
        assertThat(draftPage.content()).hasSize(1);
        assertThat(draftPage.content().getFirst().id()).isEqualTo(draft.getId());
        assertThat(draftPage.totalElements()).isEqualTo(1);

        Publication withDraft = Publication.reserve(owner, draft, "fp-1", NOW);
        Publication withoutDraft = Publication.reserve(owner, null, "fp-2", NOW);
        var pubPage = PublicationDtos.PublicationPageResponse.from(
                new PageImpl<>(List.of(withDraft, withoutDraft), PageRequest.of(0, 20), 2));
        assertThat(pubPage.content()).hasSize(2);
        assertThat(pubPage.content().get(0).draftId()).isEqualTo(draft.getId());
        assertThat(pubPage.content().get(1).draftId()).isNull();
    }
}
