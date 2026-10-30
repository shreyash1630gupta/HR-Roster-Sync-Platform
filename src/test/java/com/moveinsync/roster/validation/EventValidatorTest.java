package com.moveinsync.roster.validation;

import com.moveinsync.roster.cache.OfficeCacheService;
import com.moveinsync.roster.domain.dto.EmployeeDataDto;
import com.moveinsync.roster.domain.dto.SyncEventRequest;
import com.moveinsync.roster.domain.entity.Office;
import com.moveinsync.roster.domain.entity.Tenant;
import com.moveinsync.roster.domain.enums.EventType;
import com.moveinsync.roster.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EventValidator Contextual Tests (UT-1.2)")
class EventValidatorTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private OfficeCacheService officeCacheService;

    private EventValidator eventValidator;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        CreateEventValidator createValidator = new CreateEventValidator();
        UpdateEventValidator updateValidator = new UpdateEventValidator();
        DeactivateEventValidator deactivateValidator = new DeactivateEventValidator();

        eventValidator = new EventValidator(createValidator, updateValidator, deactivateValidator, tenantRepository, officeCacheService);
    }

    @Test
    @DisplayName("UT-1.2: Rejects payload with unconfigured/unknown office code")
    void shouldRejectUnknownOfficeCode() {
        when(tenantRepository.existsById(tenantId)).thenReturn(true);
        when(officeCacheService.getOffice(tenantId, "UNKNOWN-OFFICE")).thenReturn(Optional.empty());

        SyncEventRequest request = SyncEventRequest.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(EventType.CREATE)
                .tenantId(tenantId.toString())
                .externalEmployeeId("EMP-10042")
                .sourceVersion(1L)
                .timestamp(Instant.now())
                .data(EmployeeDataDto.builder()
                        .name("Rahul Sharma")
                        .email("rahul@infosys.com")
                        .officeCode("UNKNOWN-OFFICE")
                        .build())
                .build();

        ValidationResult result = eventValidator.validate(request);

        assertFalse(result.isValid(), "Validation should fail for unknown office code");
        assertTrue(result.getErrors().contains("Unknown office code"));
    }

    @Test
    @DisplayName("Accepts valid event with active office code")
    void shouldAcceptValidOfficeCode() {
        when(tenantRepository.existsById(tenantId)).thenReturn(true);
        Office office = Office.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .code("BLR-EC")
                .name("Electronic City Campus")
                .city("Bengaluru")
                .active(true)
                .build();
        when(officeCacheService.getOffice(tenantId, "BLR-EC")).thenReturn(Optional.of(office));

        SyncEventRequest request = SyncEventRequest.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(EventType.CREATE)
                .tenantId(tenantId.toString())
                .externalEmployeeId("EMP-10042")
                .sourceVersion(1L)
                .timestamp(Instant.now())
                .data(EmployeeDataDto.builder()
                        .name("Rahul Sharma")
                        .email("rahul@infosys.com")
                        .officeCode("BLR-EC")
                        .build())
                .build();

        ValidationResult result = eventValidator.validate(request);

        assertTrue(result.isValid());
        assertTrue(result.getErrors().isEmpty());
    }
}

