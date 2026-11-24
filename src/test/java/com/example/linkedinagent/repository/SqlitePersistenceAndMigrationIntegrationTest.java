package com.example.linkedinagent.repository;

import com.example.linkedinagent.domain.AppUser;
import com.example.linkedinagent.domain.AuditEvent;
import com.example.linkedinagent.domain.ContentDraft;
import com.example.linkedinagent.domain.LinkedInAuthorization;
import com.example.linkedinagent.domain.OAuthTransaction;
import com.example.linkedinagent.domain.Opportunity;
import com.example.linkedinagent.domain.Publication;
import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlitePersistenceAndMigrationIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    @Test
    void flywayUpgradesPopulatedV1DatabaseToLatestPreservingDataAndEnforcingCaseInsensitiveEmailAndForeignKeys(
            @TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("upgrade-v1-to-latest.db");
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

        Flyway flywayLatest = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/sqlite")
                .load();
        flywayLatest.migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");

            try (PreparedStatement selectUser = connection.prepareStatement(
                    "SELECT email, display_name, password_hash FROM app_user WHERE id = ?")) {
                selectUser.setString(1, userId);
                try (ResultSet rs = selectUser.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("email")).isEqualTo("Legacy.User@Example.com");
                    assertThat(rs.getString("display_name")).isEqualTo("Legacy User");
                    assertThat(rs.getString("password_hash")).isEmpty();
                }
            }

            try (PreparedStatement selectDraft = connection.prepareStatement(
                    "SELECT status, scheduled_at FROM content_draft WHERE id = ?")) {
                selectDraft.setString(1, draftId);
                try (ResultSet rs = selectDraft.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("status")).isEqualTo("APPROVED");
                    assertThat(rs.getString("scheduled_at")).isNull();
                }
            }

            try (PreparedStatement insertDuplicate = connection.prepareStatement("""
                    INSERT INTO app_user (id, email, display_name, password_hash, status, created_at, updated_at)
                    VALUES (?, 'legacy.user@example.com', 'Duplicate Case', 'hash', 'ACTIVE', ?, ?)
                    """)) {
                insertDuplicate.setString(1, UUID.randomUUID().toString());
                insertDuplicate.setString(2, NOW.toString());
                insertDuplicate.setString(3, NOW.toString());
                assertThatThrownBy(insertDuplicate::executeUpdate).isInstanceOf(SQLException.class);
            }

            try (PreparedStatement deleteDraft = connection.prepareStatement(
                    "DELETE FROM content_draft WHERE id = ?")) {
                deleteDraft.setString(1, draftId);
                deleteDraft.executeUpdate();
            }
            try (PreparedStatement selectDraftId = connection.prepareStatement(
                    "SELECT draft_id FROM publication WHERE id = ?")) {
                selectDraftId.setString(1, publicationId);
                try (ResultSet rs = selectDraftId.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("draft_id")).isNull();
                }
            }

            try (PreparedStatement deleteUser = connection.prepareStatement(
                    "DELETE FROM app_user WHERE id = ?")) {
                deleteUser.setString(1, userId);
                deleteUser.executeUpdate();
            }
            try (PreparedStatement countPublications = connection.prepareStatement(
                    "SELECT count(*) FROM publication WHERE id = ?")) {
                countPublications.setString(1, publicationId);
                try (ResultSet rs = countPublications.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isZero();
                }
            }
        }
    }

    @Test
    void oracleMigrationsDefineEveryEntityTableColumnAndCaseInsensitiveEmailIndex() throws Exception {
        String oracleV1 = readResource("db/migration/oracle/V1__Create_initial_schema.sql");
        String oracleV2 = readResource("db/migration/oracle/V2__Add_local_user_credentials.sql");
        String oracleV3 = readResource("db/migration/oracle/V3__Add_draft_scheduling.sql");
        String sqliteV1 = readResource("db/migration/sqlite/V1__Create_initial_schema.sql");
        String sqliteV2 = readResource("db/migration/sqlite/V2__Add_local_user_credentials.sql");
        String sqliteV3 = readResource("db/migration/sqlite/V3__Add_draft_scheduling.sql");

        Map<String, Set<String>> oracleColumns = extractSchemaColumns(oracleV1 + "\n" + oracleV2 + "\n" + oracleV3);
        Map<String, Set<String>> sqliteColumns = extractSchemaColumns(sqliteV1 + "\n" + sqliteV2 + "\n" + sqliteV3);

        assertThat(oracleColumns).isEqualTo(sqliteColumns);
        assertThat(oracleColumns.get("app_user")).contains("password_hash");
        assertThat(oracleColumns.get("content_draft")).contains("scheduled_at");
        assertThat(oracleV2)
                .containsIgnoringCase("ADD password_hash VARCHAR2(255 CHAR)")
                .containsIgnoringCase("CREATE UNIQUE INDEX uq_app_user_email_normalized")
                .containsIgnoringCase("ON app_user(LOWER(email))");
        assertThat(oracleV3)
                .containsIgnoringCase("ADD scheduled_at VARCHAR2(64 CHAR)")
                .containsIgnoringCase("CREATE INDEX idx_content_draft_scheduled");

        List<Class<?>> entities = List.of(
                AppUser.class,
                LinkedInAuthorization.class,
                ContentDraft.class,
                Publication.class,
                OAuthTransaction.class,
                AuditEvent.class,
                Opportunity.class);
        for (Class<?> entityClass : entities) {
            Table table = entityClass.getAnnotation(Table.class);
            assertThat(table).isNotNull();
            String tableName = table.name().toLowerCase(Locale.ROOT);
            assertThat(oracleColumns).containsKey(tableName);

            for (Field field : entityClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Column column = field.getAnnotation(Column.class);
                JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
                if (column != null) {
                    assertThat(column.columnDefinition())
                            .withFailMessage("Non-portable columnDefinition on %s.%s",
                                    entityClass.getSimpleName(), field.getName())
                            .isEmpty();
                }
                String columnName = joinColumn != null && !joinColumn.name().isEmpty()
                        ? joinColumn.name()
                        : (column != null && !column.name().isEmpty()
                                ? column.name()
                                : camelToSnake(field.getName()));
                assertThat(oracleColumns.get(tableName))
                        .withFailMessage("Missing column %s.%s in Oracle migrations", tableName, columnName)
                        .contains(columnName.toLowerCase(Locale.ROOT));
            }
        }
    }

    private static String readResource(String path) throws Exception {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, Set<String>> extractSchemaColumns(String ddl) {
        Map<String, Set<String>> schema = new LinkedHashMap<>();
        Pattern createTable = Pattern.compile(
                "CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*?)\\);",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher tableMatcher = createTable.matcher(ddl);
        while (tableMatcher.find()) {
            String tableName = tableMatcher.group(1).toLowerCase(Locale.ROOT);
            Set<String> columns = schema.computeIfAbsent(tableName, ignored -> new LinkedHashSet<>());
            for (String rawLine : tableMatcher.group(2).split("\\R")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("--")
                        || line.toUpperCase(Locale.ROOT).startsWith("CONSTRAINT ")) {
                    continue;
                }
                String colName = line.split("\\s+")[0].replaceAll("[,()]", "").toLowerCase(Locale.ROOT);
                if (!colName.isEmpty()) {
                    columns.add(colName);
                }
            }
        }
        Pattern alterAdd = Pattern.compile(
                "ALTER\\s+TABLE\\s+(\\w+)\\s+ADD\\s+(?:COLUMN\\s+)?(\\w+)",
                Pattern.CASE_INSENSITIVE);
        Matcher alterMatcher = alterAdd.matcher(ddl);
        while (alterMatcher.find()) {
            schema.computeIfAbsent(alterMatcher.group(1).toLowerCase(Locale.ROOT), ignored -> new LinkedHashSet<>())
                    .add(alterMatcher.group(2).toLowerCase(Locale.ROOT));
        }
        return schema;
    }

    private static String camelToSnake(String value) {
        return value.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
