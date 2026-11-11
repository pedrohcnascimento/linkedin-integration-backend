package com.example.linkedinagent.service;

import com.example.linkedinagent.domain.AppUser;
import com.example.linkedinagent.exception.EmailAlreadyRegisteredException;
import com.example.linkedinagent.exception.PasswordTooLongException;
import com.example.linkedinagent.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;

@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);
    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final AuditService auditService;

    public RegistrationService(
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this(appUserRepository, passwordEncoder, clock, null);
    }

    @Autowired
    public RegistrationService(
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder,
            Clock clock,
            AuditService auditService) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.auditService = auditService;
    }

    @Transactional
    public AppUser register(String email, String displayName, String password) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES) {
            throw new PasswordTooLongException();
        }
        if (appUserRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyRegisteredException();
        }

        AppUser user = AppUser.createNew(
                normalizedEmail,
                displayName.trim(),
                passwordEncoder.encode(password),
                clock.instant());
        AppUser savedUser;
        try {
            savedUser = appUserRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new EmailAlreadyRegisteredException();
        }
        if (auditService != null) {
            auditService.recordForUser(
                    savedUser.getId(),
                    "USER_REGISTERED",
                    "APP_USER",
                    savedUser.getId().toString(),
                    "SUCCESS",
                    null);
        }
        log.info("Registered local user account userId={}", savedUser.getId());
        return savedUser;
    }
}
