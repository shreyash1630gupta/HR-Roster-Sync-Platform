package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.entity.Booking;
import com.moveinsync.roster.domain.entity.OutboxEvent;
import com.moveinsync.roster.domain.entity.ProcessedOutboxEvent;
import com.moveinsync.roster.domain.entity.SeatAssignment;
import com.moveinsync.roster.repository.BookingRepository;
import com.moveinsync.roster.repository.ProcessedOutboxEventRepository;
import com.moveinsync.roster.repository.SeatAssignmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles downstream transport domain side-effects resulting from roster updates.
 * Guarantees consumer idempotency via the processed_outbox_events ledger and
 * atomic execution of booking cancellations and seat deallocations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownstreamSideEffectHandler {

    private final ProcessedOutboxEventRepository processedOutboxEventRepository;
    private final BookingRepository bookingRepository;
    private final SeatAssignmentRepository seatAssignmentRepository;

    /**
     * Executes downstream side effects when an employee is deactivated.
     * Cancels future active bookings and frees assigned cab seats in a single atomic transaction.
     * Guarantees idempotency: replaying the same outbox event produces zero duplicate side effects.
     *
     * @param event the EMPLOYEE_DEACTIVATED outbox event
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleEmployeeDeactivated(OutboxEvent event) {
        UUID outboxEventId = event.getId();

        // 1. Consumer Idempotency Guard
        if (outboxEventId != null && processedOutboxEventRepository.findByOutboxEventId(outboxEventId).isPresent()) {
            log.info("Consumer idempotency: Outbox event {} was already processed. Skipping duplicate side effects.", outboxEventId);
            return;
        }

        UUID tenantId = event.getTenantId();
        UUID employeeId = event.getAggregateId();

        if (tenantId == null || employeeId == null) {
            log.warn("Malformed outbox event missing tenantId or aggregateId: outboxEventId={}", outboxEventId);
            return;
        }

        Instant now = Instant.now();

        // 2. Cancel Future Active Bookings (pickup_at > now and status = ACTIVE)
        List<Booking> activeFutureBookings = bookingRepository
                .findByTenantIdAndEmployeeIdAndStatusAndPickupAtAfter(tenantId, employeeId, "ACTIVE", now);

        for (Booking booking : activeFutureBookings) {
            booking.setStatus("CANCELLED_EMPLOYEE_EXIT");
            booking.setCancellationReason("Employee deactivated");
            booking.setCancelledAt(now);
            bookingRepository.save(booking);
        }

        // 3. Free Assigned Seats (status = ASSIGNED)
        List<SeatAssignment> assignedSeats = seatAssignmentRepository
                .findByTenantIdAndEmployeeIdAndStatus(tenantId, employeeId, "ASSIGNED");

        for (SeatAssignment seat : assignedSeats) {
            seat.setStatus("FREED");
            seat.setFreedAt(now);
            seatAssignmentRepository.save(seat);
        }

        // 4. Record Consumer Idempotency Ledger
        if (outboxEventId != null) {
            try {
                ProcessedOutboxEvent processedEvent = ProcessedOutboxEvent.builder()
                        .outboxEventId(outboxEventId)
                        .build();
                processedOutboxEventRepository.save(processedEvent);
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                log.info("Concurrent consumer execution detected for outboxEventId={}, treating as idempotent duplicate", outboxEventId);
                return;
            }
        }

        log.info("Successfully applied deactivation side effects: tenantId={}, employeeId={}, cancelledBookings={}, freedSeats={}",
                tenantId, employeeId, activeFutureBookings.size(), assignedSeats.size());
    }

    /**
     * Cancels an individual booking idempotently.
     *
     * @param bookingId the booking ID
     * @return true if booking was active and is now cancelled, false if already inactive/cancelled
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean cancelBooking(UUID bookingId) {
        Optional<Booking> opt = bookingRepository.findById(bookingId);
        if (opt.isEmpty()) {
            return false;
        }
        Booking booking = opt.get();
        if (!"ACTIVE".equals(booking.getStatus())) {
            log.debug("Booking {} is already in status {}, no-op.", bookingId, booking.getStatus());
            return false;
        }
        booking.setStatus("CANCELLED_EMPLOYEE_EXIT");
        booking.setCancellationReason("Employee deactivated");
        booking.setCancelledAt(Instant.now());
        bookingRepository.save(booking);
        return true;
    }

    /**
     * Frees an individual cab seat assignment idempotently.
     *
     * @param seatId the seat assignment ID
     * @return true if seat was assigned and is now freed, false if already freed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean freeSeatAssignment(UUID seatId) {
        Optional<SeatAssignment> opt = seatAssignmentRepository.findById(seatId);
        if (opt.isEmpty()) {
            return false;
        }
        SeatAssignment seat = opt.get();
        if (!"ASSIGNED".equals(seat.getStatus())) {
            log.debug("Seat assignment {} is already in status {}, no-op.", seatId, seat.getStatus());
            return false;
        }
        seat.setStatus("FREED");
        seat.setFreedAt(Instant.now());
        seatAssignmentRepository.save(seat);
        return true;
    }
}
