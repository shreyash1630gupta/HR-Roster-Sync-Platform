package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.entity.OutboxEvent;
import com.moveinsync.roster.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Scheduled daemon poller implementing the Transactional Outbox Pattern.
 * Reads pending outbox records using SELECT ... FOR UPDATE SKIP LOCKED,
 * dispatches them via EventPublisher, and maintains exponential backoff with jitter
 * or transitions failed events to DEAD_LETTER after 10 consecutive failed retries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPoller {

    public static final int MAX_RETRIES = 10;
    public static final long MAX_BACKOFF_SECONDS = 60;

    private final OutboxEventRepository outboxEventRepository;
    private final EventPublisher eventPublisher;
    private final Random random = new Random();

    @Value("${roster.outbox.poller.enabled:true}")
    private boolean pollerEnabled = true;

    @Value("${roster.outbox.poller.batch-size:100}")
    private int batchSize = 100;

    @Value("${roster.outbox.poller.jitter-enabled:false}")
    private boolean jitterEnabled = false;

    /**
     * Scheduled polling tick (runs every 500ms by default).
     */
    @Scheduled(fixedDelayString = "${roster.outbox.poller.delay-ms:500}")
    public void pollAndPublishScheduled() {
        if (!pollerEnabled) {
            return;
        }
        try {
            processPendingEvents();
        } catch (Exception ex) {
            log.error("Unexpected error in OutboxPoller scheduled cycle: {}", ex.getMessage());
        }
    }

    /**
     * Polls and processes a batch of pending events.
     * Uses SELECT ... FOR UPDATE SKIP LOCKED to ensure multiple poller nodes or threads
     * never process the same record concurrently.
     *
     * @return the number of events successfully published in this execution
     */
    @Transactional
    public int processPendingEvents() {
        Instant now = Instant.now();
        List<OutboxEvent> pending = outboxEventRepository.findPendingEventsForPublishing(now, PageRequest.of(0, batchSize));
        if (pending.isEmpty()) {
            return 0;
        }

        int publishedCount = 0;
        for (OutboxEvent event : pending) {
            try {
                eventPublisher.publish(event);

                event.setStatus("PUBLISHED");
                event.setPublishedAt(Instant.now());
                event.setLastError(null);
                outboxEventRepository.save(event);
                publishedCount++;
                log.debug("Outbox event published successfully: id={}, type={}", event.getId(), event.getEventType());
            } catch (Exception ex) {
                handlePublishFailure(event, ex);
            }
        }
        return publishedCount;
    }

    /**
     * Handles failure to publish an outbox event by updating retry count,
     * calculating exponential backoff, and marking as DEAD_LETTER when max retries are exceeded.
     *
     * @param event the failed outbox event
     * @param ex the exception encountered
     */
    public void handlePublishFailure(OutboxEvent event, Exception ex) {
        int currentRetries = (event.getRetryCount() == null ? 0 : event.getRetryCount());
        int newRetryCount = currentRetries + 1;
        event.setRetryCount(newRetryCount);

        // Sanitize error message: zero PII (redact emails, phone numbers)
        String rawMsg = ex.getMessage() != null ? ex.getMessage() : "Publish error";
        String sanitized = rawMsg.replaceAll("[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\\.[a-zA-Z0-9-.]+", "[REDACTED_EMAIL]")
                .replaceAll("\\b\\d{10}\\b", "[REDACTED_PHONE]");
        String errorMsg = ex.getClass().getSimpleName() + ": " + sanitized;
        event.setLastError(errorMsg);

        if (newRetryCount >= MAX_RETRIES) {
            event.setStatus("DEAD_LETTER");
            event.setNextAttemptAt(Instant.now().plus(Duration.ofDays(365)));
            log.error("Outbox event transitioned to DEAD_LETTER after {} failed attempts: id={}, type={}",
                    newRetryCount, event.getId(), event.getEventType());
        } else {
            Duration backoff = calculateBackoff(newRetryCount - 1);
            long backoffSeconds = backoff.toSeconds();
            if (jitterEnabled) {
                long jitter = random.nextLong(Math.max(1, backoffSeconds / 10 + 1));
                backoffSeconds += jitter;
            }
            event.setNextAttemptAt(Instant.now().plusSeconds(backoffSeconds));
            log.warn("Outbox event publish failed (attempt {}/{}): id={}, nextAttemptIn={}s",
                    newRetryCount, MAX_RETRIES, event.getId(), backoffSeconds);
        }

        outboxEventRepository.save(event);
    }

    /**
     * Calculates exponential backoff duration based on retry count.
     * Delays: 0 -> 1s, 1 -> 2s, 2 -> 4s, 3 -> 8s, 4 -> 16s, 5 -> 32s, 6+ -> 60s.
     *
     * @param retryCount zero-based retry count
     * @return the Duration to delay next attempt
     */
    public Duration calculateBackoff(int retryCount) {
        if (retryCount <= 0) {
            return Duration.ofSeconds(1);
        }
        long seconds = 1L << retryCount; // 2^retryCount
        if (seconds > MAX_BACKOFF_SECONDS || retryCount >= 6) {
            seconds = MAX_BACKOFF_SECONDS;
        }
        return Duration.ofSeconds(seconds);
    }

    /**
     * Retrieves all events in DEAD_LETTER status for a tenant.
     */
    @Transactional(readOnly = true)
    public List<OutboxEvent> getDeadLetterEvents(UUID tenantId) {
        return outboxEventRepository.findByTenantIdAndStatus(tenantId, "DEAD_LETTER");
    }

    /**
     * Operator action to reset a DEAD_LETTER event back to PENDING for immediate retry.
     */
    @Transactional
    public OutboxEvent replayDeadLetterEvent(UUID eventId, UUID tenantId) {
        OutboxEvent event = outboxEventRepository.findById(eventId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Outbox event not found"));

        if (!event.getTenantId().equals(tenantId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Tenant mismatch for outbox event");
        }

        if (!"DEAD_LETTER".equals(event.getStatus())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Event is not in DEAD_LETTER status: " + event.getStatus());
        }

        event.setStatus("PENDING");
        event.setRetryCount(0);
        event.setNextAttemptAt(Instant.now());
        event.setLastError(null);
        log.info("Operator replayed dead-letter outbox event: id={}, tenantId={}", eventId, tenantId);
        return outboxEventRepository.save(event);
    }

    // Setters for testability
    public void setPollerEnabled(boolean pollerEnabled) {
        this.pollerEnabled = pollerEnabled;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public void setJitterEnabled(boolean jitterEnabled) {
        this.jitterEnabled = jitterEnabled;
    }
}
