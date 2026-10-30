package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.dto.EmployeeDataDto;
import com.moveinsync.roster.domain.dto.TransportFieldUpdateRequest;
import com.moveinsync.roster.domain.entity.Employee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FieldOwnershipService Unit Tests (UT-2.5, UT-2.6)")
class FieldOwnershipServiceTest {

    private FieldOwnershipService fieldOwnershipService;

    @BeforeEach
    void setUp() {
        fieldOwnershipService = new FieldOwnershipService();
    }

    @Test
    @DisplayName("UT-2.5: Sanitizes HR payload by stripping transport-owned fields")
    void shouldStripTransportFieldsFromHrPayload() {
        EmployeeDataDto data = EmployeeDataDto.builder()
                .name("Arun Kumar")
                .email("arun@example.com")
                .officeCode("BLR-EC")
                .pickupPoint("Gate 2, Forum Mall")
                .specialAssistanceNeeds("Wheelchair access")
                .dropPoint("Silk Board")
                .preferredCabType("Sedan")
                .build();

        fieldOwnershipService.sanitizeHrPayload(data);

        assertEquals("Arun Kumar", data.getName());
        assertEquals("arun@example.com", data.getEmail());
        assertEquals("BLR-EC", data.getOfficeCode());
        assertNull(data.getPickupPoint(), "pickupPoint must be stripped from HR payload");
        assertNull(data.getSpecialAssistanceNeeds(), "specialAssistanceNeeds must be stripped from HR payload");
        assertNull(data.getDropPoint(), "dropPoint must be stripped from HR payload");
        assertNull(data.getPreferredCabType(), "preferredCabType must be stripped from HR payload");
    }

    @Test
    @DisplayName("Applies transport field updates to employee entity")
    void shouldApplyTransportFields() {
        Employee employee = Employee.builder()
                .name("Arun Kumar")
                .email("arun@example.com")
                .officeCode("BLR-EC")
                .pickupPoint("Old Point")
                .build();

        TransportFieldUpdateRequest request = TransportFieldUpdateRequest.builder()
                .pickupPoint("New Gate 3")
                .specialAssistanceNeeds("Ramp needed")
                .dropPoint("HSR Layout")
                .preferredCabType("SUV")
                .build();

        fieldOwnershipService.applyTransportFields(employee, request);

        assertEquals("Arun Kumar", employee.getName(), "HR field name must remain unchanged");
        assertEquals("arun@example.com", employee.getEmail(), "HR field email must remain unchanged");
        assertEquals("New Gate 3", employee.getPickupPoint());
        assertEquals("Ramp needed", employee.getSpecialAssistanceNeeds());
        assertEquals("HSR Layout", employee.getDropPoint());
        assertEquals("SUV", employee.getPreferredCabType());
    }

    @Test
    @DisplayName("UT-2.6: Transport Admin attempting to update HR-owned field throws HTTP 403 Forbidden")
    void shouldRejectWhenTransportAdminAttemptsToModifyHrField() {
        Set<String> fieldsWithHrViolation = Set.of("pickup_point", "email", "special_assistance_needs");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                fieldOwnershipService.rejectIfContainsHrFields(fieldsWithHrViolation)
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Transport Admin cannot modify HR-owned field: email"));
    }

    @Test
    @DisplayName("Transport Admin modifying only transport fields succeeds")
    void shouldAllowWhenOnlyTransportFieldsSpecified() {
        Set<String> validTransportFields = Set.of("pickup_point", "drop_point", "special_assistance_needs");

        assertDoesNotThrow(() ->
                fieldOwnershipService.rejectIfContainsHrFields(validTransportFields)
        );
    }
}

