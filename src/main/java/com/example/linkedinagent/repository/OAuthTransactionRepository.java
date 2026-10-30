package com.example.linkedinagent.repository;

import com.example.linkedinagent.domain.OAuthTransaction;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OAuthTransactionRepository extends JpaRepository<OAuthTransaction, UUID> {

    @EntityGraph(attributePaths = "appUser")
    Optional<OAuthTransaction> findByStateHash(String stateHash);

    @Transactional
    @Modifying
    @Query("""
            update OAuthTransaction transaction
            set transaction.consumedAt = :consumedAt
            where transaction.stateHash = :stateHash
              and transaction.consumedAt is null
            """)
    int markConsumedIfUnused(
            @Param("stateHash") String stateHash,
            @Param("consumedAt") Instant consumedAt);

    @Transactional
    @Modifying
    @Query("""
            delete from OAuthTransaction transaction
            where transaction.appUser.id = :appUserId
              and transaction.consumedAt is null
            """)
    int deletePendingForUser(@Param("appUserId") UUID appUserId);
}
