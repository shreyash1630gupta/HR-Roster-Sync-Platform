package com.moveinsync.roster.security;

import com.moveinsync.roster.cache.OfficeCacheService;
import com.moveinsync.roster.controller.EmployeeController;
import com.moveinsync.roster.domain.dto.EmployeeDataDto;
import com.moveinsync.roster.domain.dto.EmployeeDetailsDto;
import com.moveinsync.roster.domain.dto.SyncEventRequest;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.domain.enums.EventType;
import com.moveinsync.roster.domain.enums.UserRole;
import com.moveinsync.roster.repository.TenantRepository;
import com.moveinsync.roster.service.EmployeeService;
import com.moveinsync.roster.service.SyncEventService;
import com.moveinsync.roster.validation.CreateEventValidator;
import com.moveinsync.roster.validation.DeactivateEventValidator;
import com.moveinsync.roster.validation.EventValidator;
import com.moveinsync.roster.validation.UpdateEventValidator;
import com.moveinsync.roster.validation.ValidationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Multi-Tenant Data Isolation & Boundary Tests (Phase 5: ST-5.4, Task 5.3)")
class TenantIsolationIntegrationTest {

    @Mock
    private EmployeeService employeeService;

    @Mock
    private SyncEventService syncEventService;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private OfficeCacheService officeCacheService;

    private EmployeeController employeeController;
    private EventValidator eventValidator;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        employeeController = new EmployeeController(employeeService, syncEventService);

        CreateEventValidator createValidator = new CreateEventValidator();
        UpdateEventValidator updateValidator = new UpdateEventValidator();
        DeactivateEventValidator deactivateValidator = new DeactivateEventValidator();
        eventValidator = new EventValidator(createValidator, updateValidator, deactivateValidator, tenantRepository, officeCacheService);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("ST-5.4: Token for Tenant A querying GET /employees returns only Tenant A employees")
    void shouldReturnOnlyTenantAEmployeesForTenantAToken() {
        // Authenticate as Tenant A
        TenantContext tenantAContext = new TenantContext(tenantA, "user-a", UserRole.TRANSPORT_ADMIN);
        TenantContextHolder.setContext(tenantAContext);

        EmployeeDetailsDto empA1 = EmployeeDetailsDto.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantA)
                .externalEmployeeId("EMP-A-1")
                .status(EmployeeStatus.ACTIVE)
                .build();

        EmployeeDetailsDto empA2 = EmployeeDetailsDto.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantA)
                .externalEmployeeId("EMP-A-2")
                .status(EmployeeStatus.ACTIVE)
                .build();

        Page<EmployeeDetailsDto> tenantAPage = new PageImpl<>(List.of(empA1, empA2));
        Pageable pageable = PageRequest.of(0, 20);

        when(employeeService.getEmployees(eq(tenantA), eq(null), eq(pageable))).thenReturn(tenantAPage);

        // Even if client maliciously passed tenantB in X-Tenant-ID header, controller resolves to authenticated Tenant A
        ResponseEntity<Page<EmployeeDetailsDto>> response = employeeController.listEmployees(0, 20, null, tenantB.toString());

        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().getContent().size());
        assertTrue(response.getBody().getContent().stream().allMatch(e -> e.getTenantId().equals(tenantA)));

        // Verify service was strictly called with Tenant A, never Tenant B
        verify(employeeService, times(1)).getEmployees(eq(tenantA), eq(null), eq(pageable));
        verify(employeeService, never()).getEmployees(eq(tenantB), any(), any());
    }

    @Test
    @DisplayName("Task 5.3: EventValidator rejects payload when body tenant_id does not match authenticated jwt tenant_id")
    void shouldRejectPayloadWhenBodyTenantDoesNotMatchTokenTenant() {
        // Authenticate as Tenant A
        TenantContext tenantAContext = new TenantContext(tenantA, "hr-user-a", UserRole.HR_SYSTEM);
        TenantContextHolder.setContext(tenantAContext);

        when(tenantRepository.existsById(tenantB)).thenReturn(true);

        // Body specifies Tenant B
        SyncEventRequest request = SyncEventRequest.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(EventType.CREATE)
                .tenantId(tenantB.toString())
                .externalEmployeeId("EMP-B-100")
                .sourceVersion(1L)
                .data(EmployeeDataDto.builder()
                        .name("Bob Tenant B")
                        .email("bob@tenantb.com")
                        .officeCode("BLR-EC")
                        .build())
                .build();

        ValidationResult result = eventValidator.validate(request);

        assertFalse(result.isValid(), "Payload must be rejected due to cross-tenant violation");
        assertTrue(result.getErrors().contains("Cross-tenant violation: request tenant does not match authenticated token"));
    }
}
