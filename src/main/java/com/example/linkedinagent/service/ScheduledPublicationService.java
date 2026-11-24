package com.example.linkedinagent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class ScheduledPublicationService {

    static final Duration DEFAULT_GRACE_WINDOW = Duration.ofMinutes(60);
    static final int DEFAULT_BATCH_SIZE = 20;

    private final DraftWorkflowService workflowService;
    private final Duration graceWindow;
    private final int batchSize;

    public ScheduledPublicationService(
            DraftWorkflowService workflowService,
            @Value("${app.scheduling.grace-window:PT60M}") Duration graceWindow,
            @Value("${app.scheduling.batch-size:20}") int batchSize) {
        if (graceWindow == null || graceWindow.isNegative() || graceWindow.isZero()) {
            throw new IllegalStateException("app.scheduling.grace-window must be positive (e.g. PT60M).");
        }
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalStateException("app.scheduling.batch-size must be between 1 and 100.");
        }
        this.workflowService = workflowService;
        this.graceWindow = graceWindow;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${app.scheduling.initial-delay-ms:30000}",
            fixedDelayString = "${app.scheduling.poll-interval-ms:30000}")
    public int publishDueDrafts() {
        return workflowService.processDueScheduledDrafts(graceWindow, batchSize);
    }

    public Duration getGraceWindow() {
        return graceWindow;
    }
}
