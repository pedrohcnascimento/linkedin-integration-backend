package com.example.linkedinagent.service;

import com.example.linkedinagent.repository.AppUserRepository;
import com.example.linkedinagent.repository.LinkedInAuthorizationRepository;
import com.example.linkedinagent.repository.OAuthTransactionRepository;
import com.example.linkedinagent.repository.PublicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class DataRetentionService {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionService.class);
    private static final Duration CONSUMED_OAUTH_STATE_RETENTION = Duration.ofHours(24);
    private static final Duration STUCK_PUBLISHING_TIMEOUT = Duration.ofMinutes(2);

    private final OAuthTransactionRepository oauthTransactionRepository;
    private final LinkedInAuthorizationRepository authorizationRepository;
    private final AppUserRepository appUserRepository;
    private final PublicationRepository publicationRepository;
    private final AuditService auditService;
    private final Clock clock;

    public DataRetentionService(
            OAuthTransactionRepository oauthTransactionRepository,
            LinkedInAuthorizationRepository authorizationRepository,
            AppUserRepository appUserRepository,
            AuditService auditService,
            Clock clock) {
        this(oauthTransactionRepository, authorizationRepository, appUserRepository, null, auditService, clock);
    }

    @Autowired
    public DataRetentionService(
            OAuthTransactionRepository oauthTransactionRepository,
            LinkedInAuthorizationRepository authorizationRepository,
            AppUserRepository appUserRepository,
            PublicationRepository publicationRepository,
            AuditService auditService,
            Clock clock) {
        this.oauthTransactionRepository = oauthTransactionRepository;
        this.authorizationRepository = authorizationRepository;
        this.appUserRepository = appUserRepository;
        this.publicationRepository = publicationRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.retention.oauth-cleanup-interval-ms:900000}")
    @Transactional
    public int purgeExpiredOAuthTransactions() {
        Instant now = clock.instant();
        if (publicationRepository != null) {
            publicationRepository.markTimedOutPublishingAsOutcomeUnknown(now.minus(STUCK_PUBLISHING_TIMEOUT));
        }
        Instant consumedCutoff = now.minus(CONSUMED_OAUTH_STATE_RETENTION);
        int deleted = oauthTransactionRepository.deleteExpiredOrConsumedBefore(now, consumedCutoff);
        if (deleted > 0) {
            log.info("Purged {} expired or consumed OAuth transaction records", deleted);
        }
        return deleted;
    }

    @Transactional
    public boolean deleteUserAccountData(UUID appUserId) {
        return appUserRepository.findById(appUserId)
                .map(user -> {
                    oauthTransactionRepository.deleteAllForUser(appUserId);
                    authorizationRepository.findByAppUser_Id(appUserId)
                            .ifPresent(authorizationRepository::delete);
                    appUserRepository.delete(user);
                    appUserRepository.flush();
                    auditService.recordSystemEvent(
                            "USER_DATA_DELETED",
                            "APP_USER",
                            appUserId.toString(),
                            "DELETED",
                            "scope=local_account_and_tokens");
                    log.info("Deleted local account and associated LinkedIn authorization data for userId={}",
                            appUserId);
                    return true;
                })
                .orElse(false);
    }
}
