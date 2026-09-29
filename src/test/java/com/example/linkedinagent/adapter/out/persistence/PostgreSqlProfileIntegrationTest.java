package com.example.linkedinagent.adapter.out.persistence;

import com.example.linkedinagent.adapter.out.persistence.entity.AppUserEntity;
import com.example.linkedinagent.adapter.out.persistence.entity.LinkedInAuthorizationEntity;
import com.example.linkedinagent.adapter.out.persistence.entity.OAuthTransactionEntity;
import com.example.linkedinagent.adapter.out.persistence.repository.AppUserRepository;
import com.example.linkedinagent.adapter.out.persistence.repository.LinkedInAuthorizationRepository;
import com.example.linkedinagent.adapter.out.persistence.repository.OAuthTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("prod")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "^jdbc:postgresql:.*")
class PostgreSqlProfileIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private LinkedInAuthorizationRepository linkedInAuthorizationRepository;

    @Autowired
    private OAuthTransactionRepository oauthTransactionRepository;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredEnvironmentVariable("POSTGRES_TEST_URL"));
        registry.add("spring.datasource.username", () -> requiredEnvironmentVariable("POSTGRES_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> requiredEnvironmentVariable("POSTGRES_TEST_PASSWORD"));
        registry.add("app.linkedin.client-id", () -> "postgres-integration-test");
        registry.add("app.linkedin.client-secret", () -> "postgres-integration-test");
        registry.add("app.linkedin.redirect-uri",
                () -> "https://localhost/api/v1/linkedin/oauth/callback");
        registry.add("app.security.token-encryption-key",
                () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("app.security.cors-allowed-origins", () -> "https://localhost");
        registry.add("app.base-url", () -> "https://localhost");
    }

    @Test
    @Transactional
    void shouldRunProductionMigrationsValidateSchemaAndPersistOAuthData() {
        String databaseVersion = jdbcTemplate.queryForObject("select version()", String.class);
        Integer successfulMigrations = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success = true", Integer.class);

        assertThat(databaseVersion).contains("PostgreSQL");
        assertThat(successfulMigrations).isEqualTo(2);

        Instant now = Instant.now();
        UUID userId = UUID.randomUUID();
        AppUserEntity user = new AppUserEntity();
        user.setId(userId);
        user.setEmail(userId + "@example.test");
        user.setDisplayName("PostgreSQL integration test");
        user.setPasswordHash("test-hash");
        user.setStatus("ACTIVE");
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        appUserRepository.saveAndFlush(user);

        LinkedInAuthorizationEntity authorization = new LinkedInAuthorizationEntity();
        authorization.setId(UUID.randomUUID());
        authorization.setAppUser(user);
        authorization.setMemberSubject(UUID.randomUUID().toString());
        authorization.setEncryptedAccessToken("test-ciphertext");
        authorization.setScopes("openid profile");
        authorization.setStatus("ACTIVE");
        authorization.setCreatedAt(now);
        authorization.setUpdatedAt(now);
        linkedInAuthorizationRepository.saveAndFlush(authorization);

        String stateHash = UUID.randomUUID().toString();
        OAuthTransactionEntity transaction = new OAuthTransactionEntity();
        transaction.setId(UUID.randomUUID());
        transaction.setAppUser(user);
        transaction.setStateHash(stateHash);
        transaction.setScopes("openid profile");
        transaction.setCreatedAt(now);
        transaction.setExpiresAt(now.plusSeconds(600));
        oauthTransactionRepository.saveAndFlush(transaction);

        assertThat(appUserRepository.findById(userId))
                .get()
                .extracting(AppUserEntity::getCreatedAt)
                .isEqualTo(now);
        assertThat(linkedInAuthorizationRepository.findByAppUser_Id(userId))
                .get()
                .extracting(LinkedInAuthorizationEntity::getMemberSubject)
                .isEqualTo(authorization.getMemberSubject());
        assertThat(oauthTransactionRepository.findByStateHash(stateHash)).isPresent();
        assertThat(oauthTransactionRepository.markConsumedIfUnused(stateHash, now)).isEqualTo(1);
        assertThat(oauthTransactionRepository.markConsumedIfUnused(stateHash, now)).isZero();
    }

    private static String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is missing: " + name);
        }
        return value;
    }
}
