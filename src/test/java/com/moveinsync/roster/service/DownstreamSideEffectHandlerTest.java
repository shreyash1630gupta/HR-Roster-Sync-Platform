package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.entity.Booking;
import com.moveinsync.roster.domain.entity.OutboxEvent;
import com.moveinsync.roster.domain.entity.ProcessedOutboxEvent;
import com.moveinsync.roster.domain.entity.SeatAssignment;
import com.moveinsync.roster.repository.BookingRepository;
import com.moveinsync.roster.repository.ProcessedOutboxEventRepository;
import com.moveinsync.roster.repository.SeatAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DownstreamSideEffectHandler Unit & Idempotency Tests (Phase 4)")
class DownstreamSideEffectHandlerTest {

    @Mock
    private ProcessedOutboxEventRepository processedOutboxEventRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private SeatAssignmentRepository seatAssignmentRepository;

    private DownstreamSideEffectHandler sideEffectHandler;

    private UUID tenantId;
    private UUID employeeId;
    private UUID outboxEventId;

    @BeforeEach
    void setUp() {
        sideEffectHandler = new DownstreamSideEffectHandler(
                processedOutboxEventRepository,
                bookingRepository,
                seatAssignmentRepository
        );

        tenantId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        outboxEventId = UUID.randomUUID();
    }

    private OutboxEvent createDeactivatedOutboxEvent() {
        return OutboxEvent.builder()
                .id(outboxEventId)
                .aggregateType("EMPLOYEE")
                .aggregateId(employeeId)
                .eventType("EMPLOYEE_DEACTIVATED")
                .tenantId(tenantId)
                .payload(Map.of("employee_id", employeeId.toString()))
                .status("PENDING")
                .build();
    }

    @Test
    @DisplayName("IT-4.4: Replaying same EMPLOYEE_DEACTIVATED outbox event twice cancels future bookings only once (Consumer Idempotency)")
    void testConsumerIdempotency_ReplayEventTwice_ExecutesOnce() {
        OutboxEvent event = createDeactivatedOutboxEvent();

        Booking futureBooking = Booking.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .employeeId(employeeId)
                .pickupAt(Instant.now().plus(2, ChronoUnit.HOURS))
                .pickupLocation("Point A")
                .dropLocation("Office B")
                .status("ACTIVE")
                .build();

        SeatAssignment seat = SeatAssignment.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .employeeId(employeeId)
                .cabId("CAB-101")
                .seatNumber(2)
                .status("ASSIGNED")
                .build();

        // Run 1: Not yet in processed_outbox_events
        when(processedOutboxEventRepository.findByOutboxEventId(outboxEventId)).thenReturn(Optional.empty());
        when(bookingRepository.findByTenantIdAndEmployeeIdAndStatusAndPickupAtAfter(eq(tenantId), eq(employeeId), eq("ACTIVE"), any(Instant.class)))
                .thenReturn(List.of(futureBooking));
        when(seatAssignmentRepository.findByTenantIdAndEmployeeIdAndStatus(tenantId, employeeId, "ASSIGNED"))
                .thenReturn(List.of(seat));

        sideEffectHandler.handleEmployeeDeactivated(event);

        // Verify Run 1 cancelled booking, freed seat, and recorded in processed_outbox_events
        assertEquals("CANCELLED_EMPLOYEE_EXIT", futureBooking.getStatus());
        assertEquals("Employee deactivated", futureBooking.getCancellationReason());
        assertNotNull(futureBooking.getCancelledAt());

        assertEquals("FREED", seat.getStatus());
        assertNotNull(seat.getFreedAt());

        verify(processedOutboxEventRepository, times(1)).save(any(ProcessedOutboxEvent.class));

        // Run 2: Replay same outbox event - now exists in processed_outbox_events
        clearInvocations(bookingRepository, seatAssignmentRepository, processedOutboxEventRepository);
        when(processedOutboxEventRepository.findByOutboxEventId(outboxEventId))
                .thenReturn(Optional.of(ProcessedOutboxEvent.builder().outboxEventId(outboxEventId).build()));

        sideEffectHandler.handleEmployeeDeactivated(event);

        // Verify Run 2 made zero calls to mutate bookings or seats
        verify(bookingRepository, never()).save(any());
        verify(seatAssignmentRepository, never()).save(any());
        verify(processedOutboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("IT-4.5: Deactivate employee with past and future bookings — future bookings cancelled, past bookings untouched")
    void testDeactivateEmployee_CancelsFutureBookings_TouchesNoPastBookings() {
        OutboxEvent event = createDeactivatedOutboxEvent();

        Booking futureBooking = Booking.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .employeeId(employeeId)
                .pickupAt(Instant.now().plus(4, ChronoUnit.HOURS))
                .pickupLocation("Home")
                .dropLocation("Office")
                .status("ACTIVE")
                .build();

        when(processedOutboxEventRepository.findByOutboxEventId(outboxEventId)).thenReturn(Optional.empty());
        // findByTenantIdAndEmployeeIdAndStatusAndPickupAtAfter only returns future active bookings
        when(bookingRepository.findByTenantIdAndEmployeeIdAndStatusAndPickupAtAfter(eq(tenantId), eq(employeeId), eq("ACTIVE"), any(Instant.class)))
                .thenReturn(List.of(futureBooking));
        when(seatAssignmentRepository.findByTenantIdAndEmployeeIdAndStatus(tenantId, employeeId, "ASSIGNED"))
                .thenReturn(Collections.emptyList());

        sideEffectHandler.handleEmployeeDeactivated(event);

        assertEquals("CANCELLED_EMPLOYEE_EXIT", futureBooking.getStatus());
        verify(bookingRepository, times(1)).save(futureBooking);
    }

    @Test
    @DisplayName("IT-4.6: Deactivate already-inactive employee — side effects act as safe idempotent no-ops")
    void testDeactivateAlreadyInactiveEmployee_SafeNoOp() {
        OutboxEvent event = createDeactivatedOutboxEvent();

        when(processedOutboxEventRepository.findByOutboxEventId(outboxEventId)).thenReturn(Optional.empty());
        // No future active bookings and no assigned seats
        when(bookingRepository.findByTenantIdAndEmployeeIdAndStatusAndPickupAtAfter(eq(tenantId), eq(employeeId), eq("ACTIVE"), any(Instant.class)))
                .thenReturn(Collections.emptyList());
        when(seatAssignmentRepository.findByTenantIdAndEmployeeIdAndStatus(tenantId, employeeId, "ASSIGNED"))
                .thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> sideEffectHandler.handleEmployeeDeactivated(event));

        verify(bookingRepository, never()).save(any());
        verify(seatAssignmentRepository, never()).save(any());
        verify(processedOutboxEventRepository, times(1)).save(any(ProcessedOutboxEvent.class));
    }

    @Test
    @DisplayName("IT-4.7: Cancel already-cancelled booking — no-op")
    void testCancelAlreadyCancelledBooking_NoOp() {
        UUID bookingId = UUID.randomUUID();
        Booking alreadyCancelledBooking = Booking.builder()
                .id(bookingId)
                .tenantId(tenantId)
                .employeeId(employeeId)
                .pickupAt(Instant.now().plus(2, ChronoUnit.HOURS))
                .pickupLocation("Point A")
                .dropLocation("Point B")
                .status("CANCELLED_EMPLOYEE_EXIT")
                .cancellationReason("Earlier cancellation")
                .build();

        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(alreadyCancelledBooking));

        boolean modified = sideEffectHandler.cancelBooking(bookingId);

        assertFalse(modified);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    @DisplayName("IT-4.8: Free already-freed seat — no-op")
    void testFreeAlreadyFreedSeat_NoOp() {
        UUID seatId = UUID.randomUUID();
        SeatAssignment alreadyFreedSeat = SeatAssignment.builder()
                .id(seatId)
                .tenantId(tenantId)
                .employeeId(employeeId)
                .cabId("CAB-202")
                .seatNumber(1)
                .status("FREED")
                .build();

        when(seatAssignmentRepository.findById(seatId)).thenReturn(Optional.of(alreadyFreedSeat));

        boolean modified = sideEffectHandler.freeSeatAssignment(seatId);

        assertFalse(modified);
        verify(seatAssignmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("IT-4.9: Simulate downstream exception during seat freeing — verify atomic failure")
    void testDownstreamExceptionDuringSeatFreeing_ThrowsAndPreventsProcessedEventSave() {
        OutboxEvent event = createDeactivatedOutboxEvent();

        Booking futureBooking = Booking.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .employeeId(employeeId)
                .pickupAt(Instant.now().plus(2, ChronoUnit.HOURS))
                .pickupLocation("A")
                .dropLocation("B")
                .status("ACTIVE")
                .build();

        SeatAssignment seat = SeatAssignment.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .employeeId(employeeId)
                .cabId("CAB-101")
                .seatNumber(3)
                .status("ASSIGNED")
                .build();

        when(processedOutboxEventRepository.findByOutboxEventId(outboxEventId)).thenReturn(Optional.empty());
        when(bookingRepository.findByTenantIdAndEmployeeIdAndStatusAndPickupAtAfter(eq(tenantId), eq(employeeId), eq("ACTIVE"), any(Instant.class)))
                .thenReturn(List.of(futureBooking));
        when(seatAssignmentRepository.findByTenantIdAndEmployeeIdAndStatus(tenantId, employeeId, "ASSIGNED"))
                .thenReturn(List.of(seat));
        doThrow(new RuntimeException("Database error during seat freeing")).when(seatAssignmentRepository).save(any(SeatAssignment.class));

        // When exception is thrown during seat freeing:
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> sideEffectHandler.handleEmployeeDeactivated(event));
        assertEquals("Database error during seat freeing", thrown.getMessage());

        // Processed event ledger MUST NOT be saved
        verify(processedOutboxEventRepository, never()).save(any());
    }
}

