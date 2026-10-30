package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.entity.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InProcessEventPublisher Dispatch Tests (Phase 4)")
class InProcessEventPublisherTest {

    @Mock
    private DownstreamSideEffectHandler downstreamSideEffectHandler;

    private InProcessEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new InProcessEventPublisher(downstreamSideEffectHandler);
    }

    @Test
    @DisplayName("Routes EMPLOYEE_DEACTIVATED event to DownstreamSideEffectHandler")
    void testRoutesDeactivatedEvent() {
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .eventType("EMPLOYEE_DEACTIVATED")
                .aggregateId(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .payload(Map.of())
                .build();

        publisher.publish(event);

        verify(downstreamSideEffectHandler, times(1)).handleEmployeeDeactivated(event);
    }

    @Test
    @DisplayName("Handles other event types in-process without error")
    void testHandlesOtherEventsWithoutError() {
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .eventType("EMPLOYEE_CREATED")
                .aggregateId(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .payload(Map.of())
                .build();

        assertDoesNotThrow(() -> publisher.publish(event));

        verify(downstreamSideEffectHandler, never()).handleEmployeeDeactivated(any());
    }
}

