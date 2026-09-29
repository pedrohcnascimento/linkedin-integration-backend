package com.example.linkedinagent.application.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationServiceTest {

    private static final String PASSWORD = "unit-test-password";

    private RecordingRegistrationPort registrationPort;
    private RecordingPasswordEncoder passwordEncoder;
    private RegistrationService registrationService;

    @BeforeEach
    void setUp() {
        registrationPort = new RecordingRegistrationPort();
        passwordEncoder = new RecordingPasswordEncoder();
        registrationService = new RegistrationService(registrationPort, passwordEncoder);
    }

    @Test
    void normalizesEmailTrimsDisplayNameAndSavesEncodedPassword() {
        RegisteredUser registeredUser = registrationService.register(
                "  USER@Example.COM  ", "  Test User  ", PASSWORD);

        assertThat(registeredUser.email()).isEqualTo("user@example.com");
        assertThat(registeredUser.displayName()).isEqualTo("Test User");
        assertThat(registrationPort.savedEmail).isEqualTo("user@example.com");
        assertThat(registrationPort.savedDisplayName).isEqualTo("Test User");
        assertThat(registrationPort.savedPasswordHash).isEqualTo("encoded:" + PASSWORD);
        assertThat(passwordEncoder.encodedPassword).isEqualTo(PASSWORD);
    }

    @Test
    void rejectsDuplicateEmailBeforeEncodingOrSaving() {
        registrationPort.emailAlreadyExists = true;

        assertThatThrownBy(() -> registrationService.register("user@example.com", "Test User", PASSWORD))
                .isInstanceOf(EmailAlreadyRegisteredException.class);

        assertThat(passwordEncoder.encodedPassword).isNull();
        assertThat(registrationPort.savedEmail).isNull();
    }

    @Test
    void rejectsPasswordExceedingBcryptUtf8ByteLimitBeforeCheckingEmail() {
        String passwordWith74Utf8Bytes = "é".repeat(37);

        assertThatThrownBy(() ->
                registrationService.register("user@example.com", "Test User", passwordWith74Utf8Bytes))
                .isInstanceOf(PasswordTooLongException.class);

        assertThat(registrationPort.emailCheckCount).isZero();
        assertThat(registrationPort.savedEmail).isNull();
        assertThat(passwordEncoder.encodedPassword).isNull();
    }

    @Test
    void acceptsPasswordAtBcryptUtf8ByteLimit() {
        String passwordWith72Utf8Bytes = "é".repeat(36);

        registrationService.register("user@example.com", "Test User", passwordWith72Utf8Bytes);

        assertThat(registrationPort.savedPasswordHash).isEqualTo("encoded:" + passwordWith72Utf8Bytes);
        assertThat(passwordEncoder.encodedPassword).isEqualTo(passwordWith72Utf8Bytes);
    }

    private static class RecordingRegistrationPort implements UserRegistrationPort {
        private boolean emailAlreadyExists;
        private int emailCheckCount;
        private String savedEmail;
        private String savedDisplayName;
        private String savedPasswordHash;

        @Override
        public boolean existsByEmail(String email) {
            emailCheckCount++;
            return emailAlreadyExists;
        }

        @Override
        public RegisteredUser save(String email, String displayName, String passwordHash) {
            savedEmail = email;
            savedDisplayName = displayName;
            savedPasswordHash = passwordHash;
            return new RegisteredUser(UUID.randomUUID(), email, displayName, Instant.EPOCH);
        }
    }

    private static class RecordingPasswordEncoder implements PasswordEncoder {
        private String encodedPassword;

        @Override
        public String encode(CharSequence rawPassword) {
            encodedPassword = rawPassword.toString();
            return "encoded:" + encodedPassword;
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return ("encoded:" + rawPassword).equals(encodedPassword);
        }
    }
}
