package com.example.linkedinagent.service;

import com.example.linkedinagent.domain.AppUser;
import com.example.linkedinagent.exception.EmailAlreadyRegisteredException;
import com.example.linkedinagent.exception.PasswordTooLongException;
import com.example.linkedinagent.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    @Test
    void normalizesEmailTrimsDisplayNameAndStoresEncodedPassword() {
        AppUserRepository repository = mock(AppUserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(repository.existsByEmail("person@example.com")).thenReturn(false);
        when(encoder.encode("valid-password-123")).thenReturn("$2a$10$encodedHash");
        when(repository.saveAndFlush(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RegistrationService service = new RegistrationService(
                repository, encoder, Clock.fixed(NOW, ZoneOffset.UTC));
        AppUser saved = service.register("  PERSON@Example.com ", "  Jane Doe  ", "valid-password-123");

        assertThat(saved.getEmail()).isEqualTo("person@example.com");
        assertThat(saved.getDisplayName()).isEqualTo("Jane Doe");
        assertThat(saved.getPasswordHash()).isEqualTo("$2a$10$encodedHash");
        assertThat(saved.getStatus()).isEqualTo(AppUser.STATUS_ACTIVE);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsPasswordsOver72Utf8BytesAndTranslatesDuplicateEmailCollisions() {
        AppUserRepository repository = mock(AppUserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        RegistrationService service = new RegistrationService(
                repository, encoder, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.register("user@example.com", "User", "é".repeat(37)))
                .isInstanceOf(PasswordTooLongException.class);
        verify(repository, never()).existsByEmail(any());

        when(repository.existsByEmail("race@example.com")).thenReturn(false);
        when(encoder.encode("valid-password-123")).thenReturn("hash");
        when(repository.saveAndFlush(any(AppUser.class)))
                .thenThrow(new DataIntegrityViolationException("unique email constraint"));

        assertThatThrownBy(() -> service.register("race@example.com", "User", "valid-password-123"))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
    }
}
