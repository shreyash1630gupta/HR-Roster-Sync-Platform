package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.entity.OutboxEvent;

/**
 * Contract for dispatching events produced by the transactional outbox.
 */
public interface EventPublisher {

    /**
     * Publishes an outbox event to downstream consumers or message brokers.
     *
     * @param event the outbox event to publish
     */
    void publish(OutboxEvent event);
}

