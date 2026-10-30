package com.moveinsync.roster.metrics;

import com.moveinsync.roster.repository.OutboxEventRepository;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Micrometer & Prometheus Metrics Verification (Phase 5: IT-5.7)")
class RosterMetricsTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private PrometheusMeterRegistry registry;
    private RosterMetrics rosterMetrics;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        lenient().when(outboxEventRepository.countByStatus("PENDING")).thenReturn(42L);
        lenient().when(outboxEventRepository.countByStatus("DEAD_LETTER")).thenReturn(3L);

        rosterMetrics = new RosterMetrics(registry, outboxEventRepository);
        rosterMetrics.init();
    }

    @Test
    @DisplayName("IT-5.7: All documented metrics are exposed and scraped by Prometheus registry")
    void shouldRegisterAndExposeAllDocumentedMetrics() {
        // Record various events and timings
        rosterMetrics.recordEventReceived("CREATE");
        rosterMetrics.recordEventReceived("UPDATE");
        rosterMetrics.recordEventAccepted("CREATE");
        rosterMetrics.recordEventRejected("VALIDATION_FAILED");
        rosterMetrics.recordEventSkipped("STALE_VERSION");
        rosterMetrics.recordVersionConflict();
        rosterMetrics.recordEventProcessingTime(Duration.ofMillis(45));
        rosterMetrics.recordReconciliationDuration(Duration.ofSeconds(12));
        rosterMetrics.recordSafetyBrakeTriggered("tenant-alpha");
        rosterMetrics.recordOutboxPublishTime(Duration.ofMillis(25));

        // Scrape prometheus format
        String scrapeOutput = registry.scrape();
        assertNotNull(scrapeOutput);

        // Verify documented counters
        assertTrue(scrapeOutput.contains("sync_events_received_total"), "Must expose sync_events_received_total");
        assertTrue(scrapeOutput.contains("sync_events_accepted_total"), "Must expose sync_events_accepted_total");
        assertTrue(scrapeOutput.contains("sync_events_rejected_total"), "Must expose sync_events_rejected_total");
        assertTrue(scrapeOutput.contains("sync_events_skipped_total"), "Must expose sync_events_skipped_total");
        assertTrue(scrapeOutput.contains("sync_events_conflicts_total"), "Must expose sync_events_conflicts_total");

        // Verify documented timers
        assertTrue(scrapeOutput.contains("sync_event_processing_seconds"), "Must expose sync_event_processing_seconds");
        assertTrue(scrapeOutput.contains("reconciliation_duration_seconds"),
                "Must expose reconciliation_duration_seconds");
        assertTrue(scrapeOutput.contains("outbox_publish_seconds"), "Must expose outbox_publish_seconds");

        // Verify safety brake counter
        assertTrue(scrapeOutput.contains("reconciliation_safety_brake_triggered_total"),
                "Must expose safety brake counter");

        // Verify gauges
        assertTrue(scrapeOutput.contains("outbox_pending_count"), "Must expose outbox_pending_count gauge");
        assertTrue(scrapeOutput.contains("outbox_dead_letter_count"), "Must expose outbox_dead_letter_count gauge");

        // Verify gauge values
        assertEquals(42.0, registry.get("outbox_pending_count").gauge().value());
        assertEquals(3.0, registry.get("outbox_dead_letter_count").gauge().value());
    }

    @Test
    @DisplayName("Cardinality control: high-throughput counters tag event_type and reason, not unbounded employeeId")
    void shouldControlCardinality() {
        rosterMetrics.recordEventReceived("CREATE");
        rosterMetrics.recordEventAccepted("CREATE");
        rosterMetrics.recordEventRejected("UNKNOWN_OFFICE");

        assertNotNull(registry.find("sync_events_received_total").tag("event_type", "CREATE").counter());
        assertNotNull(registry.find("sync_events_accepted_total").tag("event_type", "CREATE").counter());
        assertNotNull(registry.find("sync_events_rejected_total").tag("reason", "UNKNOWN_OFFICE").counter());

        // Ensure no per-employee or unbounded tags exist
        assertTrue(registry.find("sync_events_received_total").counters().stream()
                .noneMatch(c -> c.getId().getTag("employee_id") != null));
    }
}
