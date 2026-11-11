package com.example.linkedinagent.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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

class SqlitePersistenceAndMigrationIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

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
}
