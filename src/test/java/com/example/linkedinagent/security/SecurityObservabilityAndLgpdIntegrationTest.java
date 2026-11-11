package com.example.linkedinagent.security;

import com.example.linkedinagent.client.LinkedInOAuthClient;
import com.example.linkedinagent.client.LinkedInPublishingClient;
import com.example.linkedinagent.domain.AppUser;
import com.example.linkedinagent.domain.AuditEvent;
import com.example.linkedinagent.domain.ContentDraft;
import com.example.linkedinagent.domain.LinkedInConnection;
import com.example.linkedinagent.domain.OAuthTransaction;
import com.example.linkedinagent.domain.Publication;
import com.example.linkedinagent.domain.PublicationResult;
import com.example.linkedinagent.dto.ApiError;
import com.example.linkedinagent.exception.GlobalExceptionHandler;
import com.example.linkedinagent.exception.LinkedInPublishingException;
import com.example.linkedinagent.repository.AppUserRepository;
import com.example.linkedinagent.repository.AuditEventRepository;
import com.example.linkedinagent.repository.ContentDraftRepository;
import com.example.linkedinagent.repository.LinkedInAuthorizationRepository;
import com.example.linkedinagent.repository.OAuthTransactionRepository;
import com.example.linkedinagent.repository.PublicationRepository;
import com.example.linkedinagent.service.DataRetentionService;
import com.example.linkedinagent.service.DraftWorkflowService;
import com.example.linkedinagent.service.LinkedInOAuthService;
import com.example.linkedinagent.service.RegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityObservabilityAndLgpdIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private LinkedInOAuthService oauthService;

    @Autowired
    private DraftWorkflowService draftWorkflowService;

    @Autowired
    private DataRetentionService dataRetentionService;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private OAuthTransactionRepository oauthTransactionRepository;

    @Autowired
    private LinkedInAuthorizationRepository authorizationRepository;

    @Autowired
    private ContentDraftRepository contentDraftRepository;

    @Autowired
    private PublicationRepository publicationRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private GlobalExceptionHandler globalExceptionHandler;

    @MockitoBean
    private LinkedInOAuthClient oauthClient;

    @MockitoBean
    private LinkedInPublishingClient publishingClient;

    @Test
    void emitsRequestCorrelationHeaderSecurityHeadersAndRestrictsActuatorExposure() throws Exception {
        String validRequestId = "req-step14-check_01";
        mockMvc.perform(get("/actuator/health")
                        .header(RequestCorrelationFilter.REQUEST_ID_HEADER, validRequestId))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestCorrelationFilter.REQUEST_ID_HEADER, validRequestId))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Permissions-Policy", "geolocation=(), microphone=(), camera=()"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());

        String forgedHeader = "bad\r\ninjected-header: true";
        String generatedHeader = mockMvc.perform(get("/actuator/health")
                        .header(RequestCorrelationFilter.REQUEST_ID_HEADER, forgedHeader))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER);
        assertThat(generatedHeader)
                .isNotBlank()
                .isNotEqualTo(forgedHeader)
                .matches("^[A-Za-z0-9._-]{1,64}$");

        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/beans"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/configprops"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void globalExceptionHandlerSanitizesUnexpectedRuntimeExceptionsAndRethrowsSecurityExceptions() {
        ResponseEntity<ApiError> response = globalExceptionHandler.handleUnexpectedRuntimeException(
                new IllegalStateException("jdbc:oracle:thin:@secret-host:1521/prod password=supersecret"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message())
                .isEqualTo("An unexpected error occurred.")
                .doesNotContain("secret-host", "supersecret", "IllegalStateException");

        assertThatThrownBy(() -> globalExceptionHandler.handleUnexpectedRuntimeException(
                new AccessDeniedException("denied")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void recordsSanitizedAuditTrailAndEnforcesLgpdRetentionAndAccountErasure() {
        String rawSecretPassword = "valid-lgpd-password-123";
        String rawAccessToken = "secret-linkedin-access-token-xyz";
        String rawRefreshToken = "secret-linkedin-refresh-token-abc";
        String rawDraftText = "Sensitive draft body text that must not be duplicated in audit metadata";

        AppUser user = registrationService.register(
                "lgpd-" + UUID.randomUUID() + "@example.com",
                "LGPD User",
                rawSecretPassword);
        UUID userId = user.getId();

        when(oauthClient.authorizationUri(any(), any())).thenAnswer(invocation -> {
            String state = invocation.getArgument(0);
            return UriComponentsBuilder.fromUriString("https://www.linkedin.com/oauth/v2/authorization")
                    .queryParam("state", state)
                    .build()
                    .toUri();
        });
        when(oauthClient.exchangeAuthorizationCode("auth-code-step14"))
                .thenReturn(new LinkedInOAuthClient.AccessToken(
                        rawAccessToken,
                        3600,
                        Optional.of(rawRefreshToken),
                        Optional.of("openid profile w_member_social")));
        when(oauthClient.userInfo(rawAccessToken))
                .thenReturn(new LinkedInOAuthClient.LinkedInMember("member-lgpd-01"));

        URI authUri = oauthService.start(userId);
        String state = UriComponentsBuilder.fromUri(authUri).build().getQueryParams().getFirst("state");
        LinkedInConnection connection = oauthService.complete(state, "auth-code-step14");
        assertThat(connection.status()).isEqualTo("ACTIVE");

        ContentDraft draft = draftWorkflowService.createDraft(userId, rawDraftText, "NONE", null, "Title");
        draftWorkflowService.approve(userId, draft.getId());

        when(publishingClient.publish(eq(rawAccessToken), any()))
                .thenReturn(new PublicationResult("urn:li:share:9001"));
        Publication published = draftWorkflowService.publish(userId, draft.getId(), "idem-step14-ok");
        assertThat(published.getStatus()).isEqualTo(Publication.STATUS_PUBLISHED);

        ContentDraft secondDraft = draftWorkflowService.createDraft(userId, rawDraftText, "NONE", null, "Second");
        draftWorkflowService.approve(userId, secondDraft.getId());
        when(publishingClient.publish(eq(rawAccessToken), any()))
                .thenThrow(new LinkedInPublishingException("LINKEDIN_RESULT_UNKNOWN", "Timeout talking to LinkedIn", true));
        assertThatThrownBy(() -> draftWorkflowService.publish(userId, secondDraft.getId(), "idem-step14-timeout"))
                .isInstanceOf(LinkedInPublishingException.class);

        oauthService.disconnect(userId);

        List<AuditEvent> userAuditEvents = auditEventRepository.findAllByAppUser_IdOrderByOccurredAtDesc(userId);
        assertThat(userAuditEvents)
                .extracting(AuditEvent::getEventType)
                .contains(
                        "USER_REGISTERED",
                        "LINKEDIN_OAUTH_STARTED",
                        "LINKEDIN_OAUTH_COMPLETED",
                        "DRAFT_APPROVED",
                        "PUBLICATION_COMPLETED",
                        "PUBLICATION_FAILED",
                        "LINKEDIN_CONNECTION_DISCONNECTED");

        for (AuditEvent event : userAuditEvents) {
            String metadata = event.getMetadataSanitized();
            if (metadata != null) {
                assertThat(metadata)
                        .doesNotContain(
                                rawSecretPassword,
                                rawAccessToken,
                                rawRefreshToken,
                                state,
                                "auth-code-step14",
                                rawDraftText,
                                "v1.");
            }
        }

        // Verify expired OAuth transaction purge
        Instant now = Instant.now();
        OAuthTransaction expiredTx = OAuthTransaction.start(
                AppUser.reference(userId),
                "expired-hash-" + UUID.randomUUID(),
                "openid",
                now.minusSeconds(1200),
                now.minusSeconds(60));
        OAuthTransaction activeTx = OAuthTransaction.start(
                AppUser.reference(userId),
                "active-hash-" + UUID.randomUUID(),
                "openid",
                now,
                now.plusSeconds(600));
        oauthTransactionRepository.saveAllAndFlush(List.of(expiredTx, activeTx));

        int purgedCount = dataRetentionService.purgeExpiredOAuthTransactions();
        assertThat(purgedCount).isGreaterThanOrEqualTo(1);
        assertThat(oauthTransactionRepository.findById(expiredTx.getId())).isEmpty();
        assertThat(oauthTransactionRepository.findById(activeTx.getId())).isPresent();

        // Verify LGPD account & token erasure + audit anonymization (ON DELETE SET NULL)
        boolean deleted = dataRetentionService.deleteUserAccountData(userId);
        assertThat(deleted).isTrue();
        assertThat(appUserRepository.findById(userId)).isEmpty();
        assertThat(authorizationRepository.findByAppUser_Id(userId)).isEmpty();
        assertThat(oauthTransactionRepository.findById(activeTx.getId())).isEmpty();
        assertThat(contentDraftRepository.findById(draft.getId())).isEmpty();
        assertThat(publicationRepository.findById(published.getId())).isEmpty();

        List<AuditEvent> deletionAudit = auditEventRepository.findAllByResourceIdOrderByOccurredAtDesc(userId.toString());
        assertThat(deletionAudit)
                .extracting(AuditEvent::getEventType)
                .contains("USER_DATA_DELETED");
    }
}
