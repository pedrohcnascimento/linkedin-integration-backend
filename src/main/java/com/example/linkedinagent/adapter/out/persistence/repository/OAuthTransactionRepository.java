package com.example.linkedinagent.adapter.out.persistence.repository;

import com.example.linkedinagent.adapter.out.persistence.entity.OAuthTransactionEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;
import java.util.Optional;

public interface OAuthTransactionRepository extends JpaRepository<OAuthTransactionEntity, UUID> {
    Optional<OAuthTransactionEntity> findByStateHash(String stateHash);

    @Modifying
    @Query("""
            update OAuthTransactionEntity transaction
            set transaction.consumedAt = :consumedAt
            where transaction.stateHash = :stateHash
              and transaction.consumedAt is null
            """)
    int markConsumedIfUnused(
            @Param("stateHash") String stateHash,
            @Param("consumedAt") java.time.Instant consumedAt);

    @Modifying
    @Query("""
            delete from OAuthTransactionEntity transaction
            where transaction.appUser.id = :appUserId
              and transaction.consumedAt is null
            """)
    int deletePendingForUser(@Param("appUserId") UUID appUserId);
}
