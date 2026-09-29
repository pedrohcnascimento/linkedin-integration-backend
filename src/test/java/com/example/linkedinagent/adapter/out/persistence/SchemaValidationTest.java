package com.example.linkedinagent.adapter.out.persistence;

import com.example.linkedinagent.adapter.out.persistence.entity.AppUserEntity;
import com.example.linkedinagent.adapter.out.persistence.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import jakarta.persistence.EntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class SchemaValidationTest {

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    void shouldSaveAndLoadAppUserWithUuidAndInstant() {
        // Given
        AppUserEntity user = new AppUserEntity();
        UUID userId = UUID.randomUUID();
        user.setId(userId);
        user.setEmail("test@example.com");
        user.setDisplayName("Test User");
        user.setPasswordHash("test-password-hash");
        user.setStatus("ACTIVE");
        Instant now = Instant.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        // When
        appUserRepository.save(user);
        appUserRepository.flush();

        // Then
        entityManager.clear();
        Optional<AppUserEntity> found = appUserRepository.findById(userId);
        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("test@example.com");
        // Check if Instant mappings work perfectly
        assertThat(found.get().getCreatedAt()).isEqualTo(now);
    }
}
