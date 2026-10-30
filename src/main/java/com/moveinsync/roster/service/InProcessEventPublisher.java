package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.entity.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * In-process implementation of the EventPublisher interface.
 * Routes outbox events to their respective internal domain handlers.
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class InProcessEventPublisher implements EventPublisher {

    private final DownstreamSideEffectHandler downstreamSideEffectHandler;

    @Override
    public void publish(OutboxEvent event) {
        log.debug("Dispatching outbox event: id={}, type={}, aggregateId={}",
                event.getId(), event.getEventType(), event.getAggregateId());

        if ("EMPLOYEE_DEACTIVATED".equalsIgnoreCase(event.getEventType())) {
            downstreamSideEffectHandler.handleEmployeeDeactivated(event);
        } else {
            // EMPLOYEE_CREATED, EMPLOYEE_UPDATED, etc.
            log.debug("Event type {} handled in-process without side-effects", event.getEventType());
        }
    }
}

