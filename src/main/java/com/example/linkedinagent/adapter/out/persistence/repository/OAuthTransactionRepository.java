package com.example.linkedinagent.adapter.out.persistence.repository;

import com.example.linkedinagent.adapter.out.persistence.entity.OAuthTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
import java.util.Optional;

public interface OAuthTransactionRepository extends JpaRepository<OAuthTransactionEntity, UUID> {
    Optional<OAuthTransactionEntity> findByStateHash(String stateHash);
}
