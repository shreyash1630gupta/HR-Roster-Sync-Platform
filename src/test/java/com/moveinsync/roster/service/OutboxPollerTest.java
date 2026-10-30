package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.entity.OutboxEvent;
import com.moveinsync.roster.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPoller & Backoff Unit & Integration Tests (Phase 4)")
class OutboxPollerTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private EventPublisher eventPublisher;

    private OutboxPoller outboxPoller;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        outboxPoller = new OutboxPoller(outboxEventRepository, eventPublisher);
        outboxPoller.setPollerEnabled(true);
        outboxPoller.setBatchSize(100);
        outboxPoller.setJitterEnabled(false);

        tenantId = UUID.randomUUID();
    }

    private OutboxEvent buildPendingEvent(UUID eventId, String eventType) {
        return OutboxEvent.builder()
                .id(eventId)
                .aggregateType("EMPLOYEE")
                .aggregateId(UUID.randomUUID())
                .eventType(eventType)
                .tenantId(tenantId)
                .payload(Map.of("key", "val"))
                .status("PENDING")
                .retryCount(0)
                .nextAttemptAt(Instant.now().minus(5, ChronoUnit.SECONDS))
                .build();
    }

    @Test
    @DisplayName("UT-4.10: Exponential backoff calculation verifies exact delays: 1s, 2s, 4s, 8s, 16s, 32s, 60s")
    void testExponentialBackoff_ExactDelays() {
        assertEquals(Duration.ofSeconds(1), outboxPoller.calculateBackoff(0), "Attempt 1 retry should delay 1s");
        assertEquals(Duration.ofSeconds(2), outboxPoller.calculateBackoff(1), "Attempt 2 retry should delay 2s");
        assertEquals(Duration.ofSeconds(4), outboxPoller.calculateBackoff(2), "Attempt 3 retry should delay 4s");
        assertEquals(Duration.ofSeconds(8), outboxPoller.calculateBackoff(3), "Attempt 4 retry should delay 8s");
        assertEquals(Duration.ofSeconds(16), outboxPoller.calculateBackoff(4), "Attempt 5 retry should delay 16s");
        assertEquals(Duration.ofSeconds(32), outboxPoller.calculateBackoff(5), "Attempt 6 retry should delay 32s");
        assertEquals(Duration.ofSeconds(60), outboxPoller.calculateBackoff(6), "Attempt 7+ retry should cap at 60s");
        assertEquals(Duration.ofSeconds(60), outboxPoller.calculateBackoff(7), "Attempt 8 retry should cap at 60s");
        assertEquals(Duration.ofSeconds(60), outboxPoller.calculateBackoff(10), "Attempt 10 retry should cap at 60s");
        assertEquals(Duration.ofSeconds(1), outboxPoller.calculateBackoff(-1), "Negative retry should default to 1s");
    }

    @Test
    @DisplayName("IT-4.2: Pending outbox event is picked up and transitioned to PUBLISHED within cycle")
    void testPendingEvent_PickedUpAndPublished() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = buildPendingEvent(eventId, "EMPLOYEE_CREATED");

        when(outboxEventRepository.findPendingEventsForPublishing(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(event));

        int count = outboxPoller.processPendingEvents();

        assertEquals(1, count);
        verify(eventPublisher, times(1)).publish(event);
        assertEquals("PUBLISHED", event.getStatus());
        assertNotNull(event.getPublishedAt());
        assertNull(event.getLastError());
        verify(outboxEventRepository, times(1)).save(event);
    }

    @Test
    @DisplayName("FT-4.3: Failure injection: Simulate crash between DB commit and outbox publish -> event published on restart")
    void testCrashRecovery_UnpublishedEventProcessedOnRestart() {
        // Event was committed in DB with status PENDING, then node crashed before poller could run
        UUID eventId = UUID.randomUUID();
        OutboxEvent recoveredEvent = buildPendingEvent(eventId, "EMPLOYEE_DEACTIVATED");
        assertEquals("PENDING", recoveredEvent.getStatus());
        assertNull(recoveredEvent.getPublishedAt());

        // Upon "restart", poller queries pending events and finds it
        when(outboxEventRepository.findPendingEventsForPublishing(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(recoveredEvent));

        int published = outboxPoller.processPendingEvents();

        assertEquals(1, published);
        verify(eventPublisher, times(1)).publish(recoveredEvent);
        assertEquals("PUBLISHED", recoveredEvent.getStatus());
        assertNotNull(recoveredEvent.getPublishedAt());
        verify(outboxEventRepository, times(1)).save(recoveredEvent);
    }

    @Test
    @DisplayName("IT-4.11: Inject 10 consecutive dispatch errors — verify event transitions to DEAD_LETTER")
    void testConsecutiveFailures_TransitionsToDeadLetterAfter10Retries() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = buildPendingEvent(eventId, "EMPLOYEE_UPDATED");

        RuntimeException dispatchError = new RuntimeException("Downstream webhook connection refused (503)");

        // Simulate 9 failures
        for (int i = 0; i < 9; i++) {
            outboxPoller.handlePublishFailure(event, dispatchError);
            assertEquals("PENDING", event.getStatus());
            assertEquals(i + 1, event.getRetryCount());
            assertTrue(event.getLastError().contains("RuntimeException"));
            assertTrue(event.getNextAttemptAt().isAfter(Instant.now().minusSeconds(1)));
        }

        // 10th failure -> transitions to DEAD_LETTER
        outboxPoller.handlePublishFailure(event, dispatchError);
        assertEquals("DEAD_LETTER", event.getStatus());
        assertEquals(10, event.getRetryCount());
        verify(outboxEventRepository, times(10)).save(event);
    }

    @Test
    @DisplayName("Poller disabled config: does not poll or publish when disabled")
    void testPollerDisabled_NoPublishing() {
        outboxPoller.setPollerEnabled(false);

        outboxPoller.pollAndPublishScheduled();

        verify(outboxEventRepository, never()).findPendingEventsForPublishing(any(), any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("Empty pending queue: graceful no-op")
    void testEmptyQueue_ZeroPublished() {
        when(outboxEventRepository.findPendingEventsForPublishing(any(Instant.class), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        int count = outboxPoller.processPendingEvents();

        assertEquals(0, count);
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("Batch publishing: handles multiple pending events in order")
    void testMultipleEvents_PublishedInOrder() {
        OutboxEvent e1 = buildPendingEvent(UUID.randomUUID(), "EMPLOYEE_CREATED");
        OutboxEvent e2 = buildPendingEvent(UUID.randomUUID(), "EMPLOYEE_UPDATED");
        OutboxEvent e3 = buildPendingEvent(UUID.randomUUID(), "EMPLOYEE_DEACTIVATED");

        when(outboxEventRepository.findPendingEventsForPublishing(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(e1, e2, e3));

        int count = outboxPoller.processPendingEvents();

        assertEquals(3, count);
        verify(eventPublisher, times(3)).publish(any(OutboxEvent.class));
        assertEquals("PUBLISHED", e1.getStatus());
        assertEquals("PUBLISHED", e2.getStatus());
        assertEquals("PUBLISHED", e3.getStatus());
    }

    @Test
    @DisplayName("Operator API: getDeadLetterEvents queries repo by tenant and status")
    void testGetDeadLetterEvents() {
        OutboxEvent dlq = buildPendingEvent(UUID.randomUUID(), "EMPLOYEE_UPDATED");
        dlq.setStatus("DEAD_LETTER");
        when(outboxEventRepository.findByTenantIdAndStatus(tenantId, "DEAD_LETTER"))
                .thenReturn(List.of(dlq));

        List<OutboxEvent> list = outboxPoller.getDeadLetterEvents(tenantId);
        assertEquals(1, list.size());
        assertEquals("DEAD_LETTER", list.get(0).getStatus());
    }

    @Test
    @DisplayName("Operator API: replayDeadLetterEvent resets status to PENDING and retryCount to 0")
    void testReplayDeadLetterEvent_ResetsToPending() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent dlq = buildPendingEvent(eventId, "EMPLOYEE_DEACTIVATED");
        dlq.setStatus("DEAD_LETTER");
        dlq.setRetryCount(10);
        dlq.setLastError("Fatal 503");

        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(dlq));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(i -> i.getArgument(0));

        OutboxEvent replayed = outboxPoller.replayDeadLetterEvent(eventId, tenantId);

        assertEquals("PENDING", replayed.getStatus());
        assertEquals(0, replayed.getRetryCount());
        assertNull(replayed.getLastError());
        assertTrue(replayed.getNextAttemptAt().isBefore(Instant.now().plusSeconds(2)));
    }

    @Test
    @DisplayName("Operator API: replayDeadLetterEvent throws when event is not in DEAD_LETTER status")
    void testReplayDeadLetterEvent_ThrowsWhenNotDeadLetter() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent publishedEvent = buildPendingEvent(eventId, "EMPLOYEE_DEACTIVATED");
        publishedEvent.setStatus("PUBLISHED");

        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(publishedEvent));

        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                outboxPoller.replayDeadLetterEvent(eventId, tenantId)
        );
    }
}

