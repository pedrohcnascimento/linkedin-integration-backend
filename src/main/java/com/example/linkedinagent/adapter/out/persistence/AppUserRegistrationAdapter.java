package com.example.linkedinagent.adapter.out.persistence;

import com.example.linkedinagent.adapter.out.persistence.entity.AppUserEntity;
import com.example.linkedinagent.adapter.out.persistence.repository.AppUserRepository;
import com.example.linkedinagent.application.auth.RegisteredUser;
import com.example.linkedinagent.application.auth.UserRegistrationPort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Repository
public class AppUserRegistrationAdapter implements UserRegistrationPort {

    private final AppUserRepository appUserRepository;

    public AppUserRegistrationAdapter(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Override
    public boolean existsByEmail(String email) {
        return appUserRepository.existsByEmail(email);
    }

    @Override
    @Transactional
    public RegisteredUser save(String email, String displayName, String passwordHash) {
        Instant now = Instant.now();
        AppUserEntity user = new AppUserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordHash);
        user.setStatus("ACTIVE");
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        AppUserEntity savedUser = appUserRepository.saveAndFlush(user);
        return new RegisteredUser(
                savedUser.getId(), savedUser.getEmail(), savedUser.getDisplayName(), savedUser.getCreatedAt());
    }
}
