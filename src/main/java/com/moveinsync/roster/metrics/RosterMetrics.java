package com.moveinsync.roster.metrics;

import com.moveinsync.roster.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Centrally manages Prometheus and Micrometer metrics across the roster sync service.
 * Enforces strict cardinality control: high-throughput counters do NOT tag tenant_id.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RosterMetrics {

    private final MeterRegistry registry;
    private final OutboxEventRepository outboxEventRepository;

    private final ConcurrentMap<String, Counter> receivedCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> acceptedCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> rejectedCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> skippedCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> safetyBrakeCounters = new ConcurrentHashMap<>();

    private Counter conflictCounter;
    private Timer eventProcessingTimer;
    private Timer reconciliationTimer;
    private Timer outboxPublishTimer;

    @PostConstruct
    public void init() {
        conflictCounter = Counter.builder("sync_events_conflicts_total")
                .description("Total version conflicts detected during ingestion")
                .register(registry);

        eventProcessingTimer = Timer.builder("sync_event_processing_seconds")
                .description("Time taken to process a single event through validation and engine")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        reconciliationTimer = Timer.builder("reconciliation_duration_seconds")
                .description("Wall-clock time for nightly reconciliation runs")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        outboxPublishTimer = Timer.builder("outbox_publish_seconds")
                .description("Time taken to dispatch an outbox event downstream")
                .register(registry);

        // Gauges for outbox monitoring
        registry.gauge("outbox_pending_count", outboxEventRepository,
                repo -> repo.countByStatus("PENDING"));

        registry.gauge("outbox_dead_letter_count", outboxEventRepository,
                repo -> repo.countByStatus("DEAD_LETTER"));
    }

    public void recordEventReceived(String eventType) {
        String type = eventType != null ? eventType : "UNKNOWN";
        receivedCounters.computeIfAbsent(type, t ->
                Counter.builder("sync_events_received_total")
                        .tag("event_type", t)
                        .register(registry)
        ).increment();
    }

    public void recordEventAccepted(String eventType) {
        String type = eventType != null ? eventType : "UNKNOWN";
        acceptedCounters.computeIfAbsent(type, t ->
                Counter.builder("sync_events_accepted_total")
                        .tag("event_type", t)
                        .register(registry)
        ).increment();
    }

    public void recordEventRejected(String reason) {
        String r = reason != null ? reason : "VALIDATION_FAILED";
        rejectedCounters.computeIfAbsent(r, k ->
                Counter.builder("sync_events_rejected_total")
                        .tag("reason", k)
                        .register(registry)
        ).increment();
    }

    public void recordEventSkipped(String reason) {
        String r = reason != null ? reason : "STALE_VERSION";
        skippedCounters.computeIfAbsent(r, k ->
                Counter.builder("sync_events_skipped_total")
                        .tag("reason", k)
                        .register(registry)
        ).increment();
    }

    public void recordVersionConflict() {
        if (conflictCounter != null) {
            conflictCounter.increment();
        }
    }

    public void recordEventProcessingTime(Duration duration) {
        if (eventProcessingTimer != null) {
            eventProcessingTimer.record(duration);
        }
    }

    public void recordReconciliationDuration(Duration duration) {
        if (reconciliationTimer != null) {
            reconciliationTimer.record(duration);
        }
    }

    public void recordSafetyBrakeTriggered(String tenantId) {
        String t = tenantId != null ? tenantId : "default";
        safetyBrakeCounters.computeIfAbsent(t, k ->
                Counter.builder("reconciliation_safety_brake_triggered_total")
                        .tag("tenant_id", k)
                        .register(registry)
        ).increment();
    }

    public void recordOutboxPublishTime(Duration duration) {
        if (outboxPublishTimer != null) {
            outboxPublishTimer.record(duration);
        }
    }
}

