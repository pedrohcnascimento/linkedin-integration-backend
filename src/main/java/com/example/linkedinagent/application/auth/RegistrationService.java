package com.example.linkedinagent.application.auth;

import com.example.linkedinagent.application.ports.out.UserRegistrationPort;
import com.example.linkedinagent.exception.EmailAlreadyRegisteredException;
import com.example.linkedinagent.exception.PasswordTooLongException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class RegistrationService {

    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

    private final UserRegistrationPort userRegistrationPort;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(UserRegistrationPort userRegistrationPort, PasswordEncoder passwordEncoder) {
        this.userRegistrationPort = userRegistrationPort;
        this.passwordEncoder = passwordEncoder;
    }

    public RegisteredUser register(String email, String displayName, String password) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES) {
            throw new PasswordTooLongException();
        }
        if (userRegistrationPort.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyRegisteredException();
        }

        return userRegistrationPort.save(
                normalizedEmail, displayName.trim(), passwordEncoder.encode(password));
    }
}
