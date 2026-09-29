package com.example.linkedinagent.adapter.out.persistence;

import com.example.linkedinagent.adapter.out.persistence.entity.LinkedInAuthorizationEntity;
import com.example.linkedinagent.adapter.out.persistence.repository.AppUserRepository;
import com.example.linkedinagent.adapter.out.persistence.repository.LinkedInAuthorizationRepository;
import com.example.linkedinagent.application.linkedin.LinkedInConnection;
import com.example.linkedinagent.application.ports.out.LinkedInAuthorizationPort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class LinkedInAuthorizationPersistenceAdapter implements LinkedInAuthorizationPort {

    private final LinkedInAuthorizationRepository authorizationRepository;
    private final AppUserRepository appUserRepository;

    public LinkedInAuthorizationPersistenceAdapter(
            LinkedInAuthorizationRepository authorizationRepository,
            AppUserRepository appUserRepository) {
        this.authorizationRepository = authorizationRepository;
        this.appUserRepository = appUserRepository;
    }

    @Override
    @Transactional
    public void save(
            UUID appUserId,
            String memberSubject,
            String encryptedAccessToken,
            String encryptedRefreshToken,
            String scopes,
            Instant expiresAt,
            Instant now) {
        LinkedInAuthorizationEntity authorization = authorizationRepository.findByAppUser_Id(appUserId)
                .orElseGet(() -> {
                    LinkedInAuthorizationEntity created = new LinkedInAuthorizationEntity();
                    created.setId(UUID.randomUUID());
                    created.setAppUser(appUserRepository.getReferenceById(appUserId));
                    created.setCreatedAt(now);
                    return created;
                });
        authorization.setMemberSubject(memberSubject);
        authorization.setEncryptedAccessToken(encryptedAccessToken);
        authorization.setEncryptedRefreshToken(encryptedRefreshToken);
        authorization.setScopes(scopes);
        authorization.setExpiresAt(expiresAt);
        authorization.setStatus("ACTIVE");
        authorization.setUpdatedAt(now);
        authorizationRepository.saveAndFlush(authorization);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LinkedInConnection> findByAppUserId(UUID appUserId) {
        return authorizationRepository.findByAppUser_Id(appUserId)
                .map(authorization -> new LinkedInConnection(
                        authorization.getMemberSubject(),
                        authorization.getScopes(),
                        authorization.getStatus(),
                        authorization.getExpiresAt(),
                        authorization.getUpdatedAt()));
    }

    @Override
    @Transactional
    public void deleteByAppUserId(UUID appUserId) {
        authorizationRepository.findByAppUser_Id(appUserId).ifPresent(authorizationRepository::delete);
    }
}
