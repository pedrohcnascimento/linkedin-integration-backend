package com.example.linkedinagent.adapter.out.persistence;

import com.example.linkedinagent.adapter.out.persistence.entity.OAuthTransactionEntity;
import com.example.linkedinagent.adapter.out.persistence.repository.AppUserRepository;
import com.example.linkedinagent.adapter.out.persistence.repository.OAuthTransactionRepository;
import com.example.linkedinagent.application.ports.out.OAuthTransactionPort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OAuthTransactionPersistenceAdapter implements OAuthTransactionPort {

    private final OAuthTransactionRepository transactionRepository;
    private final AppUserRepository appUserRepository;

    public OAuthTransactionPersistenceAdapter(
            OAuthTransactionRepository transactionRepository,
            AppUserRepository appUserRepository) {
        this.transactionRepository = transactionRepository;
        this.appUserRepository = appUserRepository;
    }

    @Override
    @Transactional
    public void create(UUID appUserId, String stateHash, String scopes, Instant createdAt, Instant expiresAt) {
        OAuthTransactionEntity transaction = new OAuthTransactionEntity();
        transaction.setId(UUID.randomUUID());
        transaction.setAppUser(appUserRepository.getReferenceById(appUserId));
        transaction.setStateHash(stateHash);
        transaction.setScopes(scopes);
        transaction.setCreatedAt(createdAt);
        transaction.setExpiresAt(expiresAt);
        transactionRepository.saveAndFlush(transaction);
    }

    @Override
    @Transactional
    public Optional<PendingOAuthTransaction> consume(String stateHash, Instant consumedAt) {
        if (transactionRepository.markConsumedIfUnused(stateHash, consumedAt) != 1) {
            return Optional.empty();
        }
        OAuthTransactionEntity transaction = transactionRepository.findByStateHash(stateHash)
                .orElseThrow(() -> new IllegalStateException("Consumed OAuth transaction could not be loaded."));
        return Optional.of(new PendingOAuthTransaction(
                transaction.getAppUser().getId(), transaction.getScopes(), transaction.getExpiresAt()));
    }

    @Override
    @Transactional
    public void cancelPendingForUser(UUID appUserId) {
        transactionRepository.deletePendingForUser(appUserId);
    }
}
