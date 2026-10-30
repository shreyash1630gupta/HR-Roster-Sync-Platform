package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.dto.EmployeeDataDto;
import com.moveinsync.roster.domain.dto.EventResultDto;
import com.moveinsync.roster.domain.dto.SyncEventRequest;
import com.moveinsync.roster.domain.entity.Employee;
import com.moveinsync.roster.domain.entity.OutboxEvent;
import com.moveinsync.roster.domain.entity.ProcessedEvent;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.domain.enums.EventType;
import com.moveinsync.roster.domain.enums.ProcessingStatus;
import com.moveinsync.roster.repository.EmployeeRepository;
import com.moveinsync.roster.repository.OutboxEventRepository;
import com.moveinsync.roster.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SyncEngine 2-Level Idempotency, Ordering & Field Ownership Tests (Phase 2)")
class SyncEngineTest {

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private FieldOwnershipService fieldOwnershipService;
    private SyncEngine syncEngine;

    private UUID tenantId;
    private final String extEmpId = "EMP-2001";

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        fieldOwnershipService = new FieldOwnershipService();
        syncEngine = new SyncEngine(employeeRepository, processedEventRepository, outboxEventRepository, fieldOwnershipService);

        lenient().when(employeeRepository.save(any(Employee.class))).thenAnswer(i -> {
            Employee e = i.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            return e;
        });
        lenient().when(employeeRepository.saveAndFlush(any(Employee.class))).thenAnswer(i -> {
            Employee e = i.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            return e;
        });
        lenient().when(processedEventRepository.save(any(ProcessedEvent.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("UT-2.2: Level 1 Idempotency - Duplicate event_id returns cached result with 0 mutations")
    void shouldReturnCachedResultOnDuplicateEventId() {
        String eventId = "evt-level1-dup";
        ProcessedEvent existingRecord = ProcessedEvent.builder()
                .eventId(eventId)
                .tenantId(tenantId)
                .externalEmployeeId(extEmpId)
                .sourceVersion(1L)
                .result(ProcessingStatus.ACCEPTED)
                .action("CREATED")
                .build();

        when(processedEventRepository.findByEventId(eventId)).thenReturn(Optional.of(existingRecord));

        SyncEventRequest request = SyncEventRequest.builder()
                .eventId(eventId)
                .tenantId("tenant-infosys")
                .externalEmployeeId(extEmpId)
                .eventType(EventType.CREATE)
                .sourceVersion(1L)
                .build();

        EventResultDto result = syncEngine.processEventInTransaction(0, request, tenantId);

        assertEquals(ProcessingStatus.ACCEPTED, result.getStatus());
        assertEquals("CREATED", result.getAction());
        assertEquals("DUPLICATE_EVENT_ID", result.getReason());

        // Zero additional mutations
        verify(employeeRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("UT-2.1: Out-of-order stale version skipped when incoming version < current version")
    void shouldSkipStaleVersionEvent() {
        String eventId = "evt-stale";
        Employee existingEmployee = Employee.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .externalEmployeeId(extEmpId)
                .currentVersion(5L)
                .status(EmployeeStatus.ACTIVE)
                .name("Current Name")
                .officeCode("BLR-EC")
                .build();

        when(processedEventRepository.findByEventId(eventId)).thenReturn(Optional.empty());
        when(employeeRepository.findByTenantIdAndExternalEmployeeId(tenantId, extEmpId))
                .thenReturn(Optional.of(existingEmployee));

        SyncEventRequest staleRequest = SyncEventRequest.builder()
                .eventId(eventId)
                .tenantId("tenant-infosys")
                .externalEmployeeId(extEmpId)
                .eventType(EventType.UPDATE)
                .sourceVersion(3L) // Stale: 3 < 5
                .data(EmployeeDataDto.builder()
                        .name("Older Stale Name")
                        .email("stale@example.com")
                        .officeCode("BLR-EC")
                        .build())
                .build();

        EventResultDto result = syncEngine.processEventInTransaction(0, staleRequest, tenantId);

        assertEquals(ProcessingStatus.SKIPPED, result.getStatus());
        assertEquals("STALE_VERSION", result.getReason());

        // Employee unchanged
        assertEquals(5L, existingEmployee.getCurrentVersion());
        assertEquals("Current Name", existingEmployee.getName());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("UT-2.3: Same version with identical payload hash skipped as DUPLICATE_REVISION")
    void shouldSkipDuplicateRevision() {
        String eventId = "evt-dup-rev";
        EmployeeDataDto data = EmployeeDataDto.builder()
                .name("Rahul Sharma")
                .email("rahul@example.com")
                .officeCode("BLR-EC")
                .build();
        String payloadHash = syncEngine.computePayloadHash(data);

        Employee existingEmployee = Employee.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .externalEmployeeId(extEmpId)
                .currentVersion(4L)
                .lastPayloadHash(payloadHash)
                .status(EmployeeStatus.ACTIVE)
                .name("Rahul Sharma")
                .email("rahul@example.com")
                .officeCode("BLR-EC")
                .build();

        when(processedEventRepository.findByEventId(eventId)).thenReturn(Optional.empty());
        when(employeeRepository.findByTenantIdAndExternalEmployeeId(tenantId, extEmpId))
                .thenReturn(Optional.of(existingEmployee));

        SyncEventRequest request = SyncEventRequest.builder()
                .eventId(eventId)
                .tenantId("tenant-infosys")
                .externalEmployeeId(extEmpId)
                .eventType(EventType.UPDATE)
                .sourceVersion(4L) // Same version 4
                .data(data)        // Identical payload
                .build();

        EventResultDto result = syncEngine.processEventInTransaction(0, request, tenantId);

        assertEquals(ProcessingStatus.SKIPPED, result.getStatus());
        assertEquals("DUPLICATE_REVISION", result.getReason());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("UT-2.4: Same version with conflicting payload quarantined as VERSION_CONFLICT")
    void shouldQuarantineVersionConflict() {
        String eventId = "evt-conflict";
        EmployeeDataDto originalData = EmployeeDataDto.builder()
                .name("Rahul Sharma")
                .email("rahul@example.com")
                .officeCode("BLR-EC")
                .build();
        String originalHash = syncEngine.computePayloadHash(originalData);

        Employee existingEmployee = Employee.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .externalEmployeeId(extEmpId)
                .currentVersion(4L)
                .lastPayloadHash(originalHash)
                .status(EmployeeStatus.ACTIVE)
                .name("Rahul Sharma")
                .email("rahul@example.com")
                .officeCode("BLR-EC")
                .build();

        when(processedEventRepository.findByEventId(eventId)).thenReturn(Optional.empty());
        when(employeeRepository.findByTenantIdAndExternalEmployeeId(tenantId, extEmpId))
                .thenReturn(Optional.of(existingEmployee));

        // Different data at the same source_version 4 (e.g. transfer to Noida)
        EmployeeDataDto conflictingData = EmployeeDataDto.builder()
                .name("Rahul Sharma")
                .email("rahul@example.com")
                .officeCode("NOIDA-SEC62") // conflicting office
                .build();

        SyncEventRequest request = SyncEventRequest.builder()
                .eventId(eventId)
                .tenantId("tenant-infosys")
                .externalEmployeeId(extEmpId)
                .eventType(EventType.UPDATE)
                .sourceVersion(4L) // Same version 4
                .data(conflictingData)
                .build();

        EventResultDto result = syncEngine.processEventInTransaction(0, request, tenantId);

        assertEquals(ProcessingStatus.CONFLICT, result.getStatus());
        assertEquals("VERSION_CONFLICT", result.getReason());

        // Database record preserved untouched
        assertEquals("BLR-EC", existingEmployee.getOfficeCode());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("UT-2.5: HR UPDATE preserves Transport-owned fields intact")
    void shouldPreserveTransportFieldsOnHrUpdate() {
        String eventId = "evt-update-preserve";
        Employee existingEmployee = Employee.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .externalEmployeeId(extEmpId)
                .currentVersion(3L)
                .name("Original Name")
                .email("original@example.com")
                .officeCode("BLR-EC")
                .pickupPoint("Gate 2, Forum Mall")       // Transport-owned
                .specialAssistanceNeeds("Wheelchair")     // Transport-owned
                .dropPoint("Silk Board")                 // Transport-owned
                .preferredCabType("Sedan")                // Transport-owned
                .status(EmployeeStatus.ACTIVE)
                .build();

        when(processedEventRepository.findByEventId(eventId)).thenReturn(Optional.empty());
        when(employeeRepository.findByTenantIdAndExternalEmployeeId(tenantId, extEmpId))
                .thenReturn(Optional.of(existingEmployee));

        EmployeeDataDto hrPayload = EmployeeDataDto.builder()
                .name("Updated Name")
                .email("updated@example.com")
                .officeCode("BLR-EC")
                .pickupPoint("Maliciously Injected Point") // HR tries to tamper
                .build();

        SyncEventRequest request = SyncEventRequest.builder()
                .eventId(eventId)
                .tenantId("tenant-infosys")
                .externalEmployeeId(extEmpId)
                .eventType(EventType.UPDATE)
                .sourceVersion(4L) // 4 > 3
                .data(hrPayload)
                .build();

        EventResultDto result = syncEngine.processEventInTransaction(0, request, tenantId);

        assertEquals(ProcessingStatus.ACCEPTED, result.getStatus());
        assertEquals("UPDATED", result.getAction());

        // Verify HR fields updated
        assertEquals("Updated Name", existingEmployee.getName());
        assertEquals("updated@example.com", existingEmployee.getEmail());
        assertEquals(4L, existingEmployee.getCurrentVersion());

        // Verify Transport fields PRESERVED untouched
        assertEquals("Gate 2, Forum Mall", existingEmployee.getPickupPoint());
        assertEquals("Wheelchair", existingEmployee.getSpecialAssistanceNeeds());
        assertEquals("Silk Board", existingEmployee.getDropPoint());
        assertEquals("Sedan", existingEmployee.getPreferredCabType());

        // Verify Outbox event written
        verify(outboxEventRepository, times(1)).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("IT-2.11: Higher-version CREATE on existing employee treated as UPDATE (rehire/re-creation)")
    void shouldTreatHigherVersionCreateAsUpdate() {
        String eventId = "evt-create-existing";
        Employee existingEmployee = Employee.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .externalEmployeeId(extEmpId)
                .currentVersion(2L)
                .status(EmployeeStatus.INACTIVE)
                .name("Old Employee")
                .officeCode("BLR-EC")
                .build();

        when(processedEventRepository.findByEventId(eventId)).thenReturn(Optional.empty());
        when(employeeRepository.findByTenantIdAndExternalEmployeeId(tenantId, extEmpId))
                .thenReturn(Optional.of(existingEmployee));

        EmployeeDataDto hrPayload = EmployeeDataDto.builder()
                .name("Rehired Employee")
                .email("rehire@example.com")
                .officeCode("BLR-EC")
                .build();

        SyncEventRequest request = SyncEventRequest.builder()
                .eventId(eventId)
                .tenantId("tenant-infosys")
                .externalEmployeeId(extEmpId)
                .eventType(EventType.CREATE) // CREATE on existing
                .sourceVersion(5L)          // 5 > 2
                .data(hrPayload)
                .build();

        EventResultDto result = syncEngine.processEventInTransaction(0, request, tenantId);

        assertEquals(ProcessingStatus.ACCEPTED, result.getStatus());
        assertEquals("UPDATED", result.getAction());
        assertEquals(EmployeeStatus.ACTIVE, existingEmployee.getStatus());
        assertEquals(5L, existingEmployee.getCurrentVersion());
        assertEquals("Rehired Employee", existingEmployee.getName());
    }

    @Test
    @DisplayName("DEACTIVATE higher-version marks employee INACTIVE and emits outbox event")
    void shouldDeactivateEmployeeOnHigherVersion() {
        String eventId = "evt-deact";
        Employee existingEmployee = Employee.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .externalEmployeeId(extEmpId)
                .currentVersion(4L)
                .status(EmployeeStatus.ACTIVE)
                .name("Active Employee")
                .build();

        when(processedEventRepository.findByEventId(eventId)).thenReturn(Optional.empty());
        when(employeeRepository.findByTenantIdAndExternalEmployeeId(tenantId, extEmpId))
                .thenReturn(Optional.of(existingEmployee));

        SyncEventRequest request = SyncEventRequest.builder()
                .eventId(eventId)
                .tenantId("tenant-infosys")
                .externalEmployeeId(extEmpId)
                .eventType(EventType.DEACTIVATE)
                .sourceVersion(6L) // 6 > 4
                .build();

        EventResultDto result = syncEngine.processEventInTransaction(0, request, tenantId);

        assertEquals(ProcessingStatus.ACCEPTED, result.getStatus());
        assertEquals("DEACTIVATED", result.getAction());
        assertEquals(EmployeeStatus.INACTIVE, existingEmployee.getStatus());
        assertEquals(6L, existingEmployee.getCurrentVersion());

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertEquals("EMPLOYEE_DEACTIVATED", outboxCaptor.getValue().getEventType());
    }

    @Test
    @DisplayName("IT-2.7: Out-of-order sequence [v3, v7, v5, v7] converges to v7")
    void shouldConvergeToLatestVersionAcrossOutOfOrderSequence() {
        // State holder to simulate database
        final Employee[] stateHolder = new Employee[1];

        // Custom repository behavior tracking stateHolder
        when(employeeRepository.findByTenantIdAndExternalEmployeeId(eq(tenantId), eq(extEmpId)))
                .thenAnswer(i -> Optional.ofNullable(stateHolder[0]));

        lenient().when(employeeRepository.save(any(Employee.class))).thenAnswer(i -> {
            Employee e = i.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            stateHolder[0] = e;
            return stateHolder[0];
        });

        lenient().when(employeeRepository.saveAndFlush(any(Employee.class))).thenAnswer(i -> {
            Employee e = i.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            stateHolder[0] = e;
            return stateHolder[0];
        });

        // 1. Ingestion v3 (CREATE)
        SyncEventRequest v3 = SyncEventRequest.builder()
                .eventId("seq-v3")
                .tenantId("tenant-infosys")
                .externalEmployeeId(extEmpId)
                .eventType(EventType.CREATE)
                .sourceVersion(3L)
                .data(EmployeeDataDto.builder().name("Name v3").email("v3@example.com").officeCode("BLR-EC").build())
                .build();
        EventResultDto r1 = syncEngine.processEventInTransaction(0, v3, tenantId);
        assertEquals(ProcessingStatus.ACCEPTED, r1.getStatus());
        assertEquals(3L, stateHolder[0].getCurrentVersion());
        assertEquals("Name v3", stateHolder[0].getName());

        // 2. Ingestion v7 (UPDATE - jumped ahead)
        SyncEventRequest v7 = SyncEventRequest.builder()
                .eventId("seq-v7")
                .tenantId("tenant-infosys")
                .externalEmployeeId(extEmpId)
                .eventType(EventType.UPDATE)
                .sourceVersion(7L)
                .data(EmployeeDataDto.builder().name("Name v7").email("v7@example.com").officeCode("NOIDA").build())
                .build();
        EventResultDto r2 = syncEngine.processEventInTransaction(1, v7, tenantId);
        assertEquals(ProcessingStatus.ACCEPTED, r2.getStatus());
        assertEquals(7L, stateHolder[0].getCurrentVersion());
        assertEquals("Name v7", stateHolder[0].getName());
        assertEquals("NOIDA", stateHolder[0].getOfficeCode());

        // 3. Ingestion v5 (UPDATE - delayed out-of-order event, 5 < 7)
        SyncEventRequest v5 = SyncEventRequest.builder()
                .eventId("seq-v5")
                .tenantId("tenant-infosys")
                .externalEmployeeId(extEmpId)
                .eventType(EventType.UPDATE)
                .sourceVersion(5L)
                .data(EmployeeDataDto.builder().name("Name v5").email("v5@example.com").officeCode("HYD").build())
                .build();
        EventResultDto r3 = syncEngine.processEventInTransaction(2, v5, tenantId);
        assertEquals(ProcessingStatus.SKIPPED, r3.getStatus());
        assertEquals("STALE_VERSION", r3.getReason());
        // Database remains v7
        assertEquals(7L, stateHolder[0].getCurrentVersion());
        assertEquals("NOIDA", stateHolder[0].getOfficeCode());

        // 4. Ingestion v7 duplicate (same version & data)
        SyncEventRequest v7Dup = SyncEventRequest.builder()
                .eventId("seq-v7-dup")
                .tenantId("tenant-infosys")
                .externalEmployeeId(extEmpId)
                .eventType(EventType.UPDATE)
                .sourceVersion(7L)
                .data(EmployeeDataDto.builder().name("Name v7").email("v7@example.com").officeCode("NOIDA").build())
                .build();
        EventResultDto r4 = syncEngine.processEventInTransaction(3, v7Dup, tenantId);
        assertEquals(ProcessingStatus.SKIPPED, r4.getStatus());
        assertEquals("DUPLICATE_REVISION", r4.getReason());

        // Final state must remain at v7 NOIDA
        assertEquals(7L, stateHolder[0].getCurrentVersion());
        assertEquals("Name v7", stateHolder[0].getName());
        assertEquals("NOIDA", stateHolder[0].getOfficeCode());
    }

    @Test
    @DisplayName("IT-2.8: Replaying identical feed of 10 events produces 0 mutations on second run")
    void shouldProduceZeroMutationsOnReplay() {
        Map<String, ProcessedEvent> processedStore = new HashMap<>();

        when(processedEventRepository.findByEventId(anyString())).thenAnswer(i -> {
            String id = i.getArgument(0);
            return Optional.ofNullable(processedStore.get(id));
        });

        when(processedEventRepository.save(any(ProcessedEvent.class))).thenAnswer(i -> {
            ProcessedEvent pe = i.getArgument(0);
            processedStore.put(pe.getEventId(), pe);
            return pe;
        });

        List<SyncEventRequest> feed = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            feed.add(SyncEventRequest.builder()
                    .eventId("replay-evt-" + i)
                    .tenantId("tenant-infosys")
                    .externalEmployeeId("REPLAY-" + i)
                    .eventType(EventType.CREATE)
                    .sourceVersion(1L)
                    .data(EmployeeDataDto.builder().name("User " + i).email("user" + i + "@example.com").officeCode("BLR-EC").build())
                    .build());
        }

        // Run 1: First feed delivery
        for (int i = 0; i < feed.size(); i++) {
            EventResultDto res = syncEngine.processEventInTransaction(i, feed.get(i), tenantId);
            assertEquals(ProcessingStatus.ACCEPTED, res.getStatus());
        }
        verify(employeeRepository, times(10)).saveAndFlush(any(Employee.class));
        verify(outboxEventRepository, times(10)).save(any(OutboxEvent.class));

        // Clear invocation counts for run 2
        clearInvocations(employeeRepository, outboxEventRepository);

        // Run 2: Exact identical replay
        for (int i = 0; i < feed.size(); i++) {
            EventResultDto res = syncEngine.processEventInTransaction(i, feed.get(i), tenantId);
            assertEquals(ProcessingStatus.ACCEPTED, res.getStatus());
            assertEquals("DUPLICATE_EVENT_ID", res.getReason());
        }

        // Second run must produce exactly ZERO employee writes and ZERO outbox writes!
        verify(employeeRepository, never()).save(any(Employee.class));
        verify(employeeRepository, never()).saveAndFlush(any(Employee.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("Edge Case: Non-CREATE event for non-existent employee returns REJECTED")
    void shouldRejectNonCreateForNonExistentEmployee() {
        String eventId = "evt-ghost-update";
        String ghostId = "EMP-NONEXISTENT";

        when(processedEventRepository.findByEventId(eventId)).thenReturn(Optional.empty());
        when(employeeRepository.findByTenantIdAndExternalEmployeeId(tenantId, ghostId)).thenReturn(Optional.empty());

        SyncEventRequest request = SyncEventRequest.builder()
                .eventId(eventId)
                .tenantId("tenant-infosys")
                .externalEmployeeId(ghostId)
                .eventType(EventType.UPDATE)
                .sourceVersion(1L)
                .data(EmployeeDataDto.builder().name("Ghost").email("ghost@example.com").officeCode("BLR-EC").build())
                .build();

        EventResultDto result = syncEngine.processEventInTransaction(0, request, tenantId);

        assertEquals(ProcessingStatus.REJECTED, result.getStatus());
        assertTrue(result.getErrors().contains("Employee not found for UPDATE"));
        verify(employeeRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Edge Case: Null and empty optional fields compute SHA-256 payload hash without NPE")
    void shouldHandleNullAndEmptyOptionalFieldsInPayloadHash() {
        EmployeeDataDto sparseData = EmployeeDataDto.builder()
                .name("Alice Minimal")
                .email("alice@example.com")
                .officeCode("BLR-EC")
                .phone(null)
                .managerId(null)
                .shift(null)
                .department(null)
                .designation(null)
                .build();

        assertDoesNotThrow(() -> {
            String hash = syncEngine.computePayloadHash(sparseData);
            assertNotNull(hash);
            assertEquals(64, hash.length(), "SHA-256 hex string must be 64 characters");
        });

        // Null object itself
        assertDoesNotThrow(() -> {
            String hash = syncEngine.computePayloadHash(null);
            assertNotNull(hash);
            assertEquals(64, hash.length());
        });
    }

    @Test
    @DisplayName("CT-2.9: Concurrency Test - 10 simultaneous threads updating same employee converge to highest version")
    void concurrencyTenSimultaneousUpdatesConvergeToHighestVersion() throws Exception {
        final Employee sharedEmployee = Employee.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .externalEmployeeId("CONCUR-EMP")
                .currentVersion(1L)
                .name("Initial Name")
                .officeCode("BLR-EC")
                .status(EmployeeStatus.ACTIVE)
                .build();

        // Thread-safe repository stubbing
        when(processedEventRepository.findByEventId(anyString())).thenReturn(Optional.empty());
        when(employeeRepository.findByTenantIdAndExternalEmployeeId(eq(tenantId), eq("CONCUR-EMP")))
                .thenReturn(Optional.of(sharedEmployee));

        when(employeeRepository.save(any(Employee.class))).thenAnswer(i -> {
            synchronized (sharedEmployee) {
                Employee update = i.getArgument(0);
                if (update.getCurrentVersion() > sharedEmployee.getCurrentVersion()) {
                    sharedEmployee.setCurrentVersion(update.getCurrentVersion());
                    sharedEmployee.setName(update.getName());
                }
                return sharedEmployee;
            }
        });

        int numThreads = 10;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(numThreads);
        java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(numThreads);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(numThreads);

        java.util.concurrent.atomic.AtomicInteger acceptedCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger skippedCount = new java.util.concurrent.atomic.AtomicInteger(0);

        // Submit versions 2 through 11 concurrently
        for (int v = 2; v <= numThreads + 1; v++) {
            final long version = v;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // Simultaneous release
                    SyncEventRequest req = SyncEventRequest.builder()
                            .eventId("concur-evt-" + version)
                            .tenantId("tenant-infosys")
                            .externalEmployeeId("CONCUR-EMP")
                            .eventType(EventType.UPDATE)
                            .sourceVersion(version)
                            .data(EmployeeDataDto.builder()
                                    .name("Name v" + version)
                                    .email("emp@example.com")
                                    .officeCode("BLR-EC")
                                    .build())
                            .build();

                    EventResultDto res = syncEngine.processEventInTransaction(0, req, tenantId);
                    if (res.getStatus() == ProcessingStatus.ACCEPTED) {
                        acceptedCount.incrementAndGet();
                    } else if (res.getStatus() == ProcessingStatus.SKIPPED) {
                        skippedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    fail("Concurrent thread failed: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown(); // Fire all 10 threads at once
        doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        // Final state MUST converge to the highest version 11
        assertEquals(11L, sharedEmployee.getCurrentVersion(), "Final converged version must be 11");
        assertEquals(10, acceptedCount.get() + skippedCount.get(), "All 10 updates must be accounted for");
    }

    @Test
    @DisplayName("E2E-6.4: Concurrency Test - 20 simultaneous threads updating same employee converge to highest version (v21)")
    void concurrencyTwentySimultaneousUpdatesConvergeToHighestVersion() throws Exception {
        final Employee sharedEmployee = Employee.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .externalEmployeeId("CONCUR-20-EMP")
                .currentVersion(1L)
                .name("Initial Name")
                .officeCode("BLR-EC")
                .status(EmployeeStatus.ACTIVE)
                .build();

        when(processedEventRepository.findByEventId(anyString())).thenReturn(Optional.empty());
        when(employeeRepository.findByTenantIdAndExternalEmployeeId(eq(tenantId), eq("CONCUR-20-EMP")))
                .thenReturn(Optional.of(sharedEmployee));

        when(employeeRepository.save(any(Employee.class))).thenAnswer(i -> {
            synchronized (sharedEmployee) {
                Employee update = i.getArgument(0);
                if (update.getCurrentVersion() > sharedEmployee.getCurrentVersion()) {
                    sharedEmployee.setCurrentVersion(update.getCurrentVersion());
                    sharedEmployee.setName(update.getName());
                }
                return sharedEmployee;
            }
        });

        int numThreads = 20;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(numThreads);
        java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(numThreads);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(numThreads);

        java.util.concurrent.atomic.AtomicInteger acceptedCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger skippedCount = new java.util.concurrent.atomic.AtomicInteger(0);

        // Submit versions 2 through 21 across 20 threads simultaneously
        for (int v = 2; v <= numThreads + 1; v++) {
            final long version = v;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // Simultaneous firing of all 20 threads
                    SyncEventRequest req = SyncEventRequest.builder()
                            .eventId("concur-20-evt-" + version)
                            .tenantId("tenant-concur")
                            .externalEmployeeId("CONCUR-20-EMP")
                            .eventType(EventType.UPDATE)
                            .sourceVersion(version)
                            .data(EmployeeDataDto.builder()
                                    .name("Name v" + version)
                                    .email("emp20@example.com")
                                    .officeCode("BLR-EC")
                                    .build())
                            .build();

                    EventResultDto res = syncEngine.processEventInTransaction(0, req, tenantId);
                    if (res.getStatus() == ProcessingStatus.ACCEPTED) {
                        acceptedCount.incrementAndGet();
                    } else if (res.getStatus() == ProcessingStatus.SKIPPED) {
                        skippedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    fail("Concurrent 20-thread worker failed: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown(); // Fire all 20 threads at once
        doneLatch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        // Final state MUST converge to the highest version 21
        assertEquals(21L, sharedEmployee.getCurrentVersion(), "Final converged version must be 21");
        assertEquals(20, acceptedCount.get() + skippedCount.get(), "All 20 updates must be accounted for");
    }

    @Test
    @DisplayName("CT-2.10: Concurrency Test - 2 simultaneous CREATE requests for same employee ID")
    void concurrencyTwoSimultaneousCreatesForSameEmployee() throws Exception {
        final Map<String, Employee> dbStore = new java.util.concurrent.ConcurrentHashMap<>();

        when(processedEventRepository.findByEventId(anyString())).thenReturn(Optional.empty());
        when(employeeRepository.findByTenantIdAndExternalEmployeeId(eq(tenantId), eq("RACE-EMP")))
                .thenAnswer(i -> Optional.ofNullable(dbStore.get("RACE-EMP")));

        when(employeeRepository.saveAndFlush(any(Employee.class))).thenAnswer(i -> {
            Employee emp = i.getArgument(0);
            if (emp.getId() == null) emp.setId(UUID.randomUUID());
            Employee existing = dbStore.putIfAbsent("RACE-EMP", emp);
            if (existing != null) {
                // Duplicate key violation simulated
                throw new org.springframework.dao.DataIntegrityViolationException("duplicate key value violates unique constraint uq_emp_tenant_ext");
            }
            return emp;
        });

        lenient().when(employeeRepository.save(any(Employee.class))).thenAnswer(i -> {
            Employee emp = i.getArgument(0);
            if (emp.getId() == null) emp.setId(UUID.randomUUID());
            dbStore.put("RACE-EMP", emp);
            return emp;
        });

        int numThreads = 2;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(numThreads);
        java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(numThreads);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(numThreads);

        java.util.concurrent.CopyOnWriteArrayList<EventResultDto> results = new java.util.concurrent.CopyOnWriteArrayList<>();

        for (int i = 0; i < 2; i++) {
            final int id = i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    SyncEventRequest req = SyncEventRequest.builder()
                            .eventId("race-create-evt-" + id)
                            .tenantId("tenant-infosys")
                            .externalEmployeeId("RACE-EMP")
                            .eventType(EventType.CREATE)
                            .sourceVersion(1L)
                            .data(EmployeeDataDto.builder()
                                    .name("Race User")
                                    .email("race@example.com")
                                    .officeCode("BLR-EC")
                                    .build())
                            .build();

                    EventResultDto res = syncEngine.processEventInTransaction(id, req, tenantId);
                    results.add(res);
                } catch (Exception e) {
                    fail("Race thread failed: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly 1 employee created in database
        assertEquals(1, dbStore.size());
        assertEquals("RACE-EMP", dbStore.get("RACE-EMP").getExternalEmployeeId());
        assertEquals(2, results.size());

        // One was accepted as CREATED, the other was gracefully resolved (e.g. DUPLICATE_REVISION)
        long createdCount = results.stream().filter(r -> "CREATED".equals(r.getAction())).count();
        assertEquals(1, createdCount, "Exactly one thread must execute CREATED action");
    }

    @Test
    @DisplayName("IT-4.1: Atomic outbox creation with employee mutation — outbox event created with aggregate and payload")
    void testIT4_1_AtomicOutboxCreationWithEmployeeMutation() {
        String eventId = "evt-outbox-atomic";
        when(processedEventRepository.findByEventId(eventId)).thenReturn(Optional.empty());
        when(employeeRepository.findByTenantIdAndExternalEmployeeId(tenantId, "EMP-OUTBOX-1")).thenReturn(Optional.empty());

        EmployeeDataDto data = EmployeeDataDto.builder()
                .name("Atomic Outbox")
                .email("outbox@example.com")
                .officeCode("BLR-EC")
                .build();

        SyncEventRequest request = SyncEventRequest.builder()
                .eventId(eventId)
                .tenantId("tenant-atomic")
                .eventType(EventType.CREATE)
                .externalEmployeeId("EMP-OUTBOX-1")
                .sourceVersion(1L)
                .data(data)
                .build();

        EventResultDto result = syncEngine.processEventInTransaction(0, request, tenantId);
        assertEquals(ProcessingStatus.ACCEPTED, result.getStatus());
        assertEquals("CREATED", result.getAction());

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        OutboxEvent outbox = outboxCaptor.getValue();

        assertNotNull(outbox);
        assertEquals("EMPLOYEE", outbox.getAggregateType());
        assertEquals(tenantId, outbox.getTenantId());
        assertEquals("EMPLOYEE_CREATED", outbox.getEventType());
        assertEquals("PENDING", outbox.getStatus());
        assertEquals(0, outbox.getRetryCount());
        assertNotNull(outbox.getPayload());
        assertEquals("EMP-OUTBOX-1", outbox.getPayload().get("external_employee_id"));
    }
}
