package com.example.linkedinagent.adapter.in.web;

import com.example.linkedinagent.application.ports.out.LinkedInAuthorizationPort;
import com.example.linkedinagent.application.ports.out.LinkedInPublishingPort;
import com.example.linkedinagent.application.ports.out.TokenCipherPort;
import com.example.linkedinagent.application.ratelimit.RateLimitStore;
import com.example.linkedinagent.domain.publishing.PublicationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
    void paginationRejectsUnsafePageSize() throws Exception {
        MockHttpSession session = loginAndRegister();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/drafts").session(session).param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));
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
