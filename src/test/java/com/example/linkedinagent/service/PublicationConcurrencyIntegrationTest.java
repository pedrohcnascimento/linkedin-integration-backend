package com.example.linkedinagent.service;

import com.example.linkedinagent.client.LinkedInPublishingClient;
import com.example.linkedinagent.domain.AppUser;
import com.example.linkedinagent.domain.ContentDraft;
import com.example.linkedinagent.domain.LinkedInAuthorization;
import com.example.linkedinagent.domain.Publication;
import com.example.linkedinagent.domain.PublicationResult;
import com.example.linkedinagent.exception.DraftWorkflowException;
import com.example.linkedinagent.repository.ContentDraftRepository;
import com.example.linkedinagent.repository.LinkedInAuthorizationRepository;
import com.example.linkedinagent.repository.PublicationRepository;
import com.example.linkedinagent.security.TokenCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Exercita concorrência multi-thread real sobre banco persistido em arquivo:
 * 1) mesma Idempotency-Key enviada simultaneamente por múltiplas threads;
 * 2) chaves distintas enviadas simultaneamente para o mesmo draft aprovado;
 * 3) execuções concorrentes do agendador sobre o mesmo draft SCHEDULED vencido.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PublicationConcurrencyIntegrationTest {

    private static final Path SQLITE_FILE = createTempDbFile();

    private static Path createTempDbFile() {
        try {
            Path tempFile = Files.createTempFile("linkedinagent-concurrency-", ".db");
            tempFile.toFile().deleteOnExit();
            return tempFile;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create temporary SQLite file for concurrency test.", exception);
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + SQLITE_FILE.toAbsolutePath());
    }

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private DraftWorkflowService draftWorkflowService;

    @Autowired
    private LinkedInAuthorizationRepository authorizationRepository;

    @Autowired
    private ContentDraftRepository contentDraftRepository;

    @Autowired
    private PublicationRepository publicationRepository;

    @Autowired
    private TokenCipher tokenCipher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private LinkedInPublishingClient publishingClient;

    private final AtomicInteger externalCallCount = new AtomicInteger();

    @BeforeEach
    void resetCounter() {
        externalCallCount.set(0);
        when(publishingClient.publish(any(), any())).thenAnswer(invocation -> {
            int call = externalCallCount.incrementAndGet();
            Thread.sleep(50);
            return new PublicationResult("urn:li:share:concurrent-" + call);
        });
    }

    @Test
    void concurrentRequestsWithSameIdempotencyKeyPublishAtMostOnce() throws Exception {
        UUID userId = registerAndAuthorizeUser();
        ContentDraft draft = draftWorkflowService.createDraft(userId, "Same-key concurrency test", "NONE", null, null);
        draftWorkflowService.approve(userId, draft.getId());

        runConcurrentPublishBarrier(6, index ->
                draftWorkflowService.publish(userId, draft.getId(), "test-idempotency-key-concurrent-same"));

        assertThat(externalCallCount.get()).isEqualTo(1);
        assertThat(contentDraftRepository.findById(draft.getId()).orElseThrow().getStatus())
                .isEqualTo(ContentDraft.STATUS_PUBLISHED);
        assertThat(publicationRepository.findAll().stream()
                .filter(pub -> draft.getId().equals(pub.getDraft() != null ? pub.getDraft().getId() : null))
                .toList())
                .singleElement()
                .satisfies(pub -> assertThat(pub.getStatus()).isEqualTo(Publication.STATUS_PUBLISHED));
    }

    @Test
    void concurrentRequestsWithDifferentIdempotencyKeysForSameDraftPublishAtMostOnce() throws Exception {
        UUID userId = registerAndAuthorizeUser();
        ContentDraft draft = draftWorkflowService.createDraft(userId, "Diff-key concurrency test", "NONE", null, null);
        draftWorkflowService.approve(userId, draft.getId());

        IntFunction<String> keyFactory = index -> "test-idempotency-key-concurrent-diff-" + index;
        runConcurrentPublishBarrier(6, index ->
                draftWorkflowService.publish(userId, draft.getId(), keyFactory.apply(index)));

        assertThat(externalCallCount.get()).isEqualTo(1);
        assertThat(contentDraftRepository.findById(draft.getId()).orElseThrow().getStatus())
                .isEqualTo(ContentDraft.STATUS_PUBLISHED);
    }

    @Test
    void concurrentSchedulerSweepsPublishDueScheduledDraftAtMostOnce() throws Exception {
        UUID userId = registerAndAuthorizeUser();
        ContentDraft draft = draftWorkflowService.createDraft(userId, "Scheduled concurrency test", "NONE", null, null);
        draftWorkflowService.approve(userId, draft.getId(), Instant.now().plusSeconds(600));

        jdbcTemplate.update(
                "UPDATE content_draft SET scheduled_at = ? WHERE id = ?",
                Instant.now().minusSeconds(60).toString(),
                draft.getId().toString());

        int threads = 4;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Barrier timeout.");
                    }
                    return draftWorkflowService.processDueScheduledDrafts(Duration.ofMinutes(60), 20);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Integer> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(externalCallCount.get()).isEqualTo(1);
        assertThat(contentDraftRepository.findById(draft.getId()).orElseThrow().getStatus())
                .isEqualTo(ContentDraft.STATUS_PUBLISHED);
    }

    private void runConcurrentPublishBarrier(
            int threads,
            java.util.function.IntFunction<Publication> action) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Publication>> futures = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                final int threadIndex = index;
                Callable<Publication> callable = () -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Barrier timeout.");
                    }
                    return action.apply(threadIndex);
                };
                futures.add(pool.submit(callable));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int succeeded = 0;
            int rejected = 0;
            for (Future<Publication> future : futures) {
                try {
                    Publication pub = future.get(15, TimeUnit.SECONDS);
                    assertThat(pub.getStatus()).isEqualTo(Publication.STATUS_PUBLISHED);
                    succeeded++;
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(DraftWorkflowException.class);
                    rejected++;
                }
            }
            assertThat(succeeded).isGreaterThanOrEqualTo(1);
            assertThat(succeeded + rejected).isEqualTo(threads);
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID registerAndAuthorizeUser() {
        AppUser user = registrationService.register(
                UUID.randomUUID() + "@example.com",
                "Concurrency User",
                "valid-test-password");
        Instant now = Instant.now();
        LinkedInAuthorization authorization = LinkedInAuthorization.createForUser(
                AppUser.reference(user.getId()), now);
        authorization.refreshTokens(
                "member-concurrent",
                tokenCipher.encrypt("access-token", user.getId().toString()),
                null,
                "openid profile w_member_social",
                now.plusSeconds(3600),
                now);
        authorizationRepository.saveAndFlush(authorization);
        return user.getId();
    }
}
