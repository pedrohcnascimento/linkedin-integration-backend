package com.example.linkedinagent.adapter.in.web;

import com.example.linkedinagent.adapter.out.persistence.repository.AuditEventRepository;
import com.example.linkedinagent.application.ports.out.LinkedInAuthorizationPort;
import com.example.linkedinagent.application.ports.out.LinkedInPublishingPort;
import com.example.linkedinagent.application.ports.out.TokenCipherPort;
import com.example.linkedinagent.application.ratelimit.RateLimitStore;
import com.example.linkedinagent.domain.publishing.PublicationResult;
import com.example.linkedinagent.exception.LinkedInPublishingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DraftControllerIntegrationTest {

    private static final String PASSWORD = "valid-test-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @MockitoBean
    private RateLimitStore rateLimitStore;

    @MockitoBean
    private LinkedInPublishingPort publishingPort;

    @MockitoBean
    private LinkedInAuthorizationPort authorizationPort;

    @MockitoBean
    private TokenCipherPort tokenCipher;

    @BeforeEach
    void setUp() {
        when(rateLimitStore.acquire(anyList())).thenReturn(Optional.empty());
        when(authorizationPort.findCredentials(any())).thenReturn(Optional.of(
                new LinkedInAuthorizationPort.LinkedInAuthorizationCredentials(
                        "member-123", "encrypted-token", "ACTIVE", Instant.now().plusSeconds(3600))));
        when(tokenCipher.decrypt("encrypted-token")).thenReturn("access-token");
        when(publishingPort.publish(any(), any())).thenReturn(new PublicationResult("urn:li:share:123"));
    }

    @Test
    void approvedDraftIsPublishedAndRepeatedSameKeyDoesNotCallLinkedInAgain() throws Exception {
        MockHttpSession session = loginAndRegister();
        UUID draftId = createDraft(session);

        mockMvc.perform(post("/api/v1/drafts/{id}/approve", draftId)
                        .session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(post("/api/v1/drafts/{id}/publish", draftId)
                        .session(session).with(csrf()).header("Idempotency-Key", "publish-key-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.externalPostId").value("urn:li:share:123"));

        mockMvc.perform(post("/api/v1/drafts/{id}/publish", draftId)
                        .session(session).with(csrf()).header("Idempotency-Key", "publish-key-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.externalPostId").value("urn:li:share:123"));

        verify(publishingPort, times(1)).publish(any(), any());
    }

    @Test
    void publishingRequiresApprovedDraftAndIdempotencyKey() throws Exception {
        MockHttpSession session = loginAndRegister();
        UUID draftId = createDraft(session);

        mockMvc.perform(post("/api/v1/drafts/{id}/publish", draftId)
                        .session(session).with(csrf()).header("Idempotency-Key", "key"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DRAFT_NOT_APPROVED"));

        mockMvc.perform(post("/api/v1/drafts/{id}/approve", draftId)
                        .session(session).with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/drafts/{id}/publish", draftId)
                        .session(session).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void draftIsOwnedByTheCreatingUser() throws Exception {
        MockHttpSession firstSession = loginAndRegister();
        UUID draftId = createDraft(firstSession);
        MockHttpSession secondSession = loginAndRegister();

        mockMvc.perform(post("/api/v1/drafts/{id}/approve", draftId)
                        .session(secondSession).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void draftsAreListedInPagesAndOnlyForTheAuthenticatedOwner() throws Exception {
        MockHttpSession firstSession = loginAndRegister();
        createDraft(firstSession);
        createDraft(firstSession);
        MockHttpSession secondSession = loginAndRegister();
        createDraft(secondSession);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/drafts").session(firstSession).param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void draftCanBeEditedOnlyBeforeApproval() throws Exception {
        MockHttpSession session = loginAndRegister();
        UUID draftId = createDraft(session);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/drafts/{id}", draftId).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Updated content","title":"Updated title"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Updated content"))
                .andExpect(jsonPath("$.title").value("Updated title"))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        mockMvc.perform(post("/api/v1/drafts/{id}/approve", draftId)
                        .session(session).with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/drafts/{id}", draftId).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Must be rejected\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRAFT_INVALID_STATE"));
    }

    @Test
    void draftIdReturnedByListCanBeEditedInTheSameAuthenticatedSession() throws Exception {
        MockHttpSession session = loginAndRegister();
        createDraft(session);

        MvcResult list = mockMvc.perform(get("/api/v1/drafts").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(list.getResponse().getContentAsString(), "$.content[0].id");

        mockMvc.perform(patch("/api/v1/drafts/{id}", id).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Updated from listed id\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.text").value("Updated from listed id"));
    }

    @Test
    void paginationRejectsUnsafePageSize() throws Exception {
        MockHttpSession session = loginAndRegister();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/drafts").session(session).param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));
    }

    @Test
    void publicationHistoryIsPagedAndScopedToAuthenticatedOwner() throws Exception {
        MockHttpSession firstSession = loginAndRegister();
        publishDraft(firstSession, createDraft(firstSession), "history-key-1");
        publishDraft(firstSession, createDraft(firstSession), "history-key-2");
        MockHttpSession secondSession = loginAndRegister();

        mockMvc.perform(get("/api/v1/publications").session(firstSession)
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/v1/publications").session(secondSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/publications"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void failedPublicationIsPersistedAndSameIdempotencyKeyIsNotRetried() throws Exception {
        MockHttpSession session = loginAndRegister();
        UUID draftId = createDraft(session);
        mockMvc.perform(post("/api/v1/drafts/{id}/approve", draftId)
                        .session(session).with(csrf()))
                .andExpect(status().isOk());
        when(publishingPort.publish(any(), any()))
                .thenThrow(new LinkedInPublishingException("LINKEDIN_UNAVAILABLE", "provider details"));

        mockMvc.perform(post("/api/v1/drafts/{id}/publish", draftId)
                        .session(session).with(csrf()).header("Idempotency-Key", "failed-key"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("LINKEDIN_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("LinkedIn publication failed."));

        mockMvc.perform(get("/api/v1/publications").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value("FAILED"))
                .andExpect(jsonPath("$.content[0].failureCode").value("LINKEDIN_UNAVAILABLE"));

        mockMvc.perform(post("/api/v1/drafts/{id}/publish", draftId)
                        .session(session).with(csrf()).header("Idempotency-Key", "failed-key"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUBLICATION_ALREADY_PROCESSED"));
        verify(publishingPort, times(1)).publish(any(), any());
    }

    @Test
    void ambiguousPublicationIsPersistedAndBlocksRetriesWithAnyKey() throws Exception {
        MockHttpSession session = loginAndRegister();
        UUID draftId = createDraft(session);
        mockMvc.perform(post("/api/v1/drafts/{id}/approve", draftId)
                        .session(session).with(csrf()))
                .andExpect(status().isOk());
        when(publishingPort.publish(any(), any()))
                .thenThrow(new LinkedInPublishingException("LINKEDIN_PROVIDER_UNAVAILABLE", "transport failure", true));

        mockMvc.perform(post("/api/v1/drafts/{id}/publish", draftId)
                        .session(session).with(csrf()).header("Idempotency-Key", "ambiguous-key"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("LINKEDIN_RESULT_UNKNOWN"));

        mockMvc.perform(get("/api/v1/publications").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("OUTCOME_UNKNOWN"))
                .andExpect(jsonPath("$.content[0].failureCode").value("LINKEDIN_RESULT_UNKNOWN"));

        mockMvc.perform(post("/api/v1/drafts/{id}/publish", draftId)
                        .session(session).with(csrf()).header("Idempotency-Key", "ambiguous-key"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUBLICATION_ALREADY_PROCESSED"));

        mockMvc.perform(post("/api/v1/drafts/{id}/publish", draftId)
                        .session(session).with(csrf()).header("Idempotency-Key", "different-key"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUBLICATION_OUTCOME_UNKNOWN"));
        verify(publishingPort, times(1)).publish(any(), any());
    }

    @Test
    void manualReconciliationAsPublishedAllowsIdempotentReplayAndPreventsDuplicatePost() throws Exception {
        MockHttpSession session = loginAndRegister();
        UUID draftId = createDraft(session);
        mockMvc.perform(post("/api/v1/drafts/{id}/approve", draftId)
                        .session(session).with(csrf()))
                .andExpect(status().isOk());
        when(publishingPort.publish(any(), any()))
                .thenThrow(new LinkedInPublishingException("LINKEDIN_PROVIDER_UNAVAILABLE", "read timeout", true));

        mockMvc.perform(post("/api/v1/drafts/{id}/publish", draftId)
                        .session(session).with(csrf()).header("Idempotency-Key", "reconcile-published-key"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("LINKEDIN_RESULT_UNKNOWN"));

        var row = jdbcTemplate.queryForMap(
                "SELECT id, app_user_id FROM publication WHERE draft_id = ? AND status = 'OUTCOME_UNKNOWN'",
                draftId.toString());
        String publicationId = row.get("id").toString();
        String appUserId = row.get("app_user_id").toString();
        UUID auditId = UUID.randomUUID();

        // Executa o procedimento manual do Cenário A (post confirmado no perfil do LinkedIn).
        int updatedPublications = jdbcTemplate.update("""
                UPDATE publication
                   SET status = 'PUBLISHED',
                       failure_code = NULL,
                       external_post_id = ?,
                       published_at = strftime('%Y-%m-%dT%H:%M:%SZ', 'now')
                 WHERE id = ?
                   AND app_user_id = ?
                   AND status IN ('PUBLISHING', 'OUTCOME_UNKNOWN')
                """, "urn:li:share:reconciled-post-1", publicationId, appUserId);
        int updatedDrafts = jdbcTemplate.update("""
                UPDATE content_draft
                   SET status = 'PUBLISHED',
                       updated_at = strftime('%Y-%m-%dT%H:%M:%SZ', 'now')
                 WHERE id = ?
                   AND app_user_id = ?
                   AND status = 'APPROVED'
                """, draftId.toString(), appUserId);
        jdbcTemplate.update("""
                INSERT INTO audit_event (
                    id, app_user_id, event_type, resource_type, resource_id,
                    outcome, occurred_at, metadata_sanitized
                ) VALUES (
                    ?, ?, 'PUBLICATION_MANUAL_RECONCILIATION', 'PUBLICATION', ?,
                    'MARKED_PUBLISHED', strftime('%Y-%m-%dT%H:%M:%SZ', 'now'), ?
                )
                """, auditId.toString(), appUserId, publicationId,
                "{\"draftId\":\"%s\",\"previousStatus\":\"OUTCOME_UNKNOWN\",\"reconciledStatus\":\"PUBLISHED\",\"verification\":\"linkedin_profile_activity\"}"
                        .formatted(draftId));
        assertThat(updatedPublications).isEqualTo(1);
        assertThat(updatedDrafts).isEqualTo(1);

        // Replay com a mesma chave devolve o registro reconciliado (201) sem nova chamada ao LinkedIn.
        mockMvc.perform(post("/api/v1/drafts/{id}/publish", draftId)
                        .session(session).with(csrf()).header("Idempotency-Key", "reconcile-published-key"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(publicationId))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.externalPostId").value("urn:li:share:reconciled-post-1"))
                .andExpect(jsonPath("$.failureCode").value(nullValue()));

        // Chave diferente não pode publicar novamente porque o draft já está PUBLISHED.
        mockMvc.perform(post("/api/v1/drafts/{id}/publish", draftId)
                        .session(session).with(csrf()).header("Idempotency-Key", "another-key-after-published"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DRAFT_NOT_APPROVED"));

        mockMvc.perform(get("/api/v1/publications").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.content[0].failureCode").value(nullValue()))
                .andExpect(jsonPath("$.content[0].publishedAt").isNotEmpty());

        var auditEvent = auditEventRepository.findById(auditId).orElseThrow();
        assertThat(auditEvent.getOutcome()).isEqualTo("MARKED_PUBLISHED");
        assertThat(auditEvent.getOccurredAt()).isNotNull();
        verify(publishingPort, times(1)).publish(any(), any());
    }

    @Test
    void manualReconciliationAsNotPublishedKeepsOldKeyBlockedAndAllowsFreshIdempotencyKey() throws Exception {
        MockHttpSession session = loginAndRegister();
        UUID draftId = createDraft(session);
        mockMvc.perform(post("/api/v1/drafts/{id}/approve", draftId)
                        .session(session).with(csrf()))
                .andExpect(status().isOk());
        when(publishingPort.publish(any(), any()))
                .thenThrow(new IllegalStateException("simulated process interruption"))
                .thenReturn(new PublicationResult("urn:li:share:published-with-fresh-key"));

        assertThatThrownBy(() -> mockMvc.perform(post("/api/v1/drafts/{id}/publish", draftId)
                .session(session).with(csrf()).header("Idempotency-Key", "interrupted-key")))
                .hasRootCauseInstanceOf(IllegalStateException.class);

        // A reserva PUBLISHING sobrevive em transação própria e bloqueia tanto a mesma chave quanto chaves novas.
        mockMvc.perform(get("/api/v1/publications").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("PUBLISHING"));
        mockMvc.perform(post("/api/v1/drafts/{id}/publish", draftId)
                        .session(session).with(csrf()).header("Idempotency-Key", "interrupted-key"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUBLICATION_ALREADY_PROCESSED"));
        mockMvc.perform(post("/api/v1/drafts/{id}/publish", draftId)
                        .session(session).with(csrf()).header("Idempotency-Key", "premature-new-key"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUBLICATION_OUTCOME_UNKNOWN"));

        var row = jdbcTemplate.queryForMap(
                "SELECT id, app_user_id FROM publication WHERE draft_id = ? AND status = 'PUBLISHING'",
                draftId.toString());
        String publicationId = row.get("id").toString();
        String appUserId = row.get("app_user_id").toString();
        UUID auditId = UUID.randomUUID();

        // Executa o procedimento manual do Cenário B (ausência confirmada no perfil do LinkedIn).
        int updatedPublications = jdbcTemplate.update("""
                UPDATE publication
                   SET status = 'FAILED',
                       failure_code = 'RECONCILED_NOT_PUBLISHED',
                       external_post_id = NULL,
                       published_at = NULL
                 WHERE id = ?
                   AND app_user_id = ?
                   AND status IN ('PUBLISHING', 'OUTCOME_UNKNOWN')
                """, publicationId, appUserId);
        jdbcTemplate.update("""
                INSERT INTO audit_event (
                    id, app_user_id, event_type, resource_type, resource_id,
                    outcome, occurred_at, metadata_sanitized
                ) VALUES (
                    ?, ?, 'PUBLICATION_MANUAL_RECONCILIATION', 'PUBLICATION', ?,
                    'MARKED_NOT_PUBLISHED', strftime('%Y-%m-%dT%H:%M:%SZ', 'now'), ?
                )
                """, auditId.toString(), appUserId, publicationId,
                "{\"draftId\":\"%s\",\"previousStatus\":\"PUBLISHING\",\"reconciledStatus\":\"FAILED\",\"failureCode\":\"RECONCILED_NOT_PUBLISHED\",\"verification\":\"linkedin_profile_activity\"}"
                        .formatted(draftId));
        assertThat(updatedPublications).isEqualTo(1);

        // A chave antiga continua bloqueada; o envio só ocorre quando o usuário envia explicitamente uma chave nova.
        mockMvc.perform(post("/api/v1/drafts/{id}/publish", draftId)
                        .session(session).with(csrf()).header("Idempotency-Key", "interrupted-key"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUBLICATION_ALREADY_PROCESSED"));

        mockMvc.perform(post("/api/v1/drafts/{id}/publish", draftId)
                        .session(session).with(csrf()).header("Idempotency-Key", "fresh-key-after-reconciliation"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.externalPostId").value("urn:li:share:published-with-fresh-key"));

        mockMvc.perform(get("/api/v1/publications").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        var auditEvent = auditEventRepository.findById(auditId).orElseThrow();
        assertThat(auditEvent.getOutcome()).isEqualTo("MARKED_NOT_PUBLISHED");
        assertThat(auditEvent.getOccurredAt()).isNotNull();
        verify(publishingPort, times(2)).publish(any(), any());
    }

    @Test
    void publicationHistoryRejectsUnsafePageSize() throws Exception {
        MockHttpSession session = loginAndRegister();

        mockMvc.perform(get("/api/v1/publications").session(session).param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));
    }

    private void publishDraft(MockHttpSession session, UUID draftId, String idempotencyKey) throws Exception {
        mockMvc.perform(post("/api/v1/drafts/{id}/approve", draftId)
                        .session(session).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/drafts/{id}/publish", draftId)
                        .session(session).with(csrf()).header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    private UUID createDraft(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/drafts")
                        .session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Hello from an approved draft","mediaCategory":"NONE"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return UUID.fromString(id);
    }

    private MockHttpSession loginAndRegister() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"Test User","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }
}
