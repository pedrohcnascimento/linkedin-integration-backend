package com.example.linkedinagent.repository;

import com.example.linkedinagent.domain.AppUser;
import com.example.linkedinagent.domain.AuditEvent;
import com.example.linkedinagent.domain.ContentDraft;
import com.example.linkedinagent.domain.LinkedInAuthorization;
import com.example.linkedinagent.domain.OAuthTransaction;
import com.example.linkedinagent.domain.Opportunity;
import com.example.linkedinagent.domain.Publication;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class SqlitePersistenceAndMigrationIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private LinkedInAuthorizationRepository authorizationRepository;

    @Autowired
    private OAuthTransactionRepository oauthTransactionRepository;

    @Autowired
    private ContentDraftRepository contentDraftRepository;

    @Autowired
    private PublicationRepository publicationRepository;

    @Autowired
    private OpportunityRepository opportunityRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayUpgradesPopulatedV1DatabaseToV2PreservingDataAndEnforcingCaseInsensitiveEmailAndForeignKeys(
            @TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("upgrade-v1-to-v2.db");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + dbFile.toAbsolutePath() + "?foreign_keys=on");
        dataSource.setDriverClassName("org.sqlite.JDBC");

        Flyway flywayV1 = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/sqlite")
                .target("1")
                .load();
        flywayV1.migrate();

        String userId = UUID.randomUUID().toString();
        String draftId = UUID.randomUUID().toString();
        String publicationId = UUID.randomUUID().toString();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            try (PreparedStatement insertUser = connection.prepareStatement("""
                    INSERT INTO app_user (id, email, display_name, status, created_at, updated_at)
                    VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                    """)) {
                insertUser.setString(1, userId);
                insertUser.setString(2, "Legacy.User@Example.com");
                insertUser.setString(3, "Legacy User");
                insertUser.setString(4, NOW.toString());
                insertUser.setString(5, NOW.toString());
                insertUser.executeUpdate();
            }
            try (PreparedStatement insertDraft = connection.prepareStatement("""
                    INSERT INTO content_draft (id, app_user_id, text, media_category, status, created_at, updated_at)
                    VALUES (?, ?, 'Legacy draft', 'NONE', 'APPROVED', ?, ?)
                    """)) {
                insertDraft.setString(1, draftId);
                insertDraft.setString(2, userId);
                insertDraft.setString(3, NOW.toString());
                insertDraft.setString(4, NOW.toString());
                insertDraft.executeUpdate();
            }
            try (PreparedStatement insertPublication = connection.prepareStatement("""
                    INSERT INTO publication (id, app_user_id, draft_id, request_fingerprint, status, created_at)
                    VALUES (?, ?, ?, 'legacy-fp', 'PUBLISHED', ?)
                    """)) {
                insertPublication.setString(1, publicationId);
                insertPublication.setString(2, userId);
                insertPublication.setString(3, draftId);
                insertPublication.setString(4, NOW.toString());
                insertPublication.executeUpdate();
            }
        }

        Flyway flywayV2 = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/sqlite")
                .load();
        flywayV2.migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");

            try (ResultSet rs = statement.executeQuery(
                    "SELECT email, display_name, password_hash FROM app_user WHERE id = '" + userId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("email")).isEqualTo("Legacy.User@Example.com");
                assertThat(rs.getString("display_name")).isEqualTo("Legacy User");
                assertThat(rs.getString("password_hash")).isEmpty();
            }

            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO app_user (id, email, display_name, password_hash, status, created_at, updated_at)
                    VALUES ('%s', 'legacy.user@example.com', 'Duplicate Case', 'hash', 'ACTIVE', '%s', '%s')
                    """.formatted(UUID.randomUUID(), NOW, NOW)))
                    .isInstanceOf(SQLException.class);

            statement.executeUpdate("DELETE FROM content_draft WHERE id = '" + draftId + "'");
            try (ResultSet rs = statement.executeQuery(
                    "SELECT draft_id FROM publication WHERE id = '" + publicationId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("draft_id")).isNull();
            }

            statement.executeUpdate("DELETE FROM app_user WHERE id = '" + userId + "'");
            try (ResultSet rs = statement.executeQuery(
                    "SELECT count(*) FROM publication WHERE id = '" + publicationId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    @Test
    @Transactional
    void persistsAndReloadsAllDomainEntitiesWithUuidAndInstantMappingsAndForeignKeysEnabled() {
        Integer foreignKeysPragma = jdbcTemplate.queryForObject("PRAGMA foreign_keys", Integer.class);
        assertThat(foreignKeysPragma).isEqualTo(1);

        AppUser user = AppUser.createNew(
                "persistence-" + UUID.randomUUID() + "@example.com",
                "Persistence User",
                "$2a$10$hash",
                NOW);
        appUserRepository.saveAndFlush(user);

        LinkedInAuthorization authorization = LinkedInAuthorization.createForUser(user, NOW);
        authorization.refreshTokens(
                "member-xyz", "v1.encrypted-access", "v1.encrypted-refresh",
                "openid profile w_member_social", NOW.plusSeconds(3600), NOW);
        authorizationRepository.saveAndFlush(authorization);

        OAuthTransaction transaction = OAuthTransaction.start(
                user, "state-hash-" + UUID.randomUUID(), "openid profile", NOW, NOW.plusSeconds(600));
        oauthTransactionRepository.saveAndFlush(transaction);

        ContentDraft draft = ContentDraft.createTextDraft(
                user, "Draft persisted in SQLite", "NONE", null, "Title", NOW);
        draft.approve(NOW.plusSeconds(30));
        contentDraftRepository.saveAndFlush(draft);

        Publication publication = Publication.reserve(user, draft, "fp-" + UUID.randomUUID(), NOW.plusSeconds(40));
        publication.markPublished("urn:li:share:777", NOW.plusSeconds(45));
        publicationRepository.saveAndFlush(publication);

        Opportunity opportunity = new Opportunity();
        opportunity.setId(UUID.randomUUID());
        opportunity.setAppUser(user);
        opportunity.setSource("MANUAL");
        opportunity.setExternalUrl("https://jobs.example.com/1");
        opportunity.setTitle("Backend Engineer");
        opportunity.setCompanyName("Example Corp");
        opportunity.setLocation("Remote");
        opportunity.setDescriptionSnapshot("Java 21 + Spring Boot");
        opportunity.setMatchScore(92);
        opportunity.setStatus("SAVED");
        opportunity.setFoundAt(NOW);
        opportunity.setExpiresAt(NOW.plusSeconds(86400));
        opportunityRepository.saveAndFlush(opportunity);

        AuditEvent auditEvent = new AuditEvent();
        auditEvent.setId(UUID.randomUUID());
        auditEvent.setAppUser(user);
        auditEvent.setEventType("PUBLICATION_COMPLETED");
        auditEvent.setResourceType("PUBLICATION");
        auditEvent.setResourceId(publication.getId().toString());
        auditEvent.setOutcome("SUCCESS");
        auditEvent.setOccurredAt(NOW.plusSeconds(45));
        auditEvent.setMetadataSanitized("{\"status\":\"PUBLISHED\"}");
        auditEventRepository.saveAndFlush(auditEvent);

        entityManager.flush();
        entityManager.clear();

        assertThat(appUserRepository.findById(user.getId()))
                .get()
                .satisfies(reloaded -> {
                    assertThat(reloaded.getEmail()).isEqualTo(user.getEmail());
                    assertThat(reloaded.getCreatedAt()).isEqualTo(NOW);
                });
        assertThat(authorizationRepository.findByAppUser_Id(user.getId()))
                .get()
                .satisfies(reloaded -> {
                    assertThat(reloaded.getMemberSubject()).isEqualTo("member-xyz");
                    assertThat(reloaded.getExpiresAt()).isEqualTo(NOW.plusSeconds(3600));
                });
        assertThat(oauthTransactionRepository.findByStateHash(transaction.getStateHash()))
                .get()
                .satisfies(reloaded -> {
                    assertThat(reloaded.getAppUser().getId()).isEqualTo(user.getId());
                    assertThat(reloaded.getExpiresAt()).isEqualTo(NOW.plusSeconds(600));
                });
        assertThat(contentDraftRepository.findByIdAndAppUser_Id(draft.getId(), user.getId()))
                .get()
                .satisfies(reloaded -> assertThat(reloaded.getApprovedAt()).isEqualTo(NOW.plusSeconds(30)));
        assertThat(publicationRepository.findById(publication.getId()))
                .get()
                .satisfies(reloaded -> {
                    assertThat(reloaded.getExternalPostId()).isEqualTo("urn:li:share:777");
                    assertThat(reloaded.getPublishedAt()).isEqualTo(NOW.plusSeconds(45));
                });
        assertThat(opportunityRepository.findById(opportunity.getId()))
                .get()
                .satisfies(reloaded -> {
                    assertThat(reloaded.getMatchScore()).isEqualTo(92);
                    assertThat(reloaded.getFoundAt()).isEqualTo(NOW);
                });
        assertThat(auditEventRepository.findById(auditEvent.getId()))
                .get()
                .satisfies(reloaded -> assertThat(reloaded.getOccurredAt()).isEqualTo(NOW.plusSeconds(45)));
    }
}
