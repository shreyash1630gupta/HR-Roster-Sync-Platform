package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.dto.EmployeeDataDto;
import com.moveinsync.roster.domain.dto.TransportFieldUpdateRequest;
import com.moveinsync.roster.domain.entity.Employee;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.Set;

@Service
public class FieldOwnershipService {

    public static final Set<String> HR_OWNED_FIELDS = Set.of(
            "name", "email", "phone", "manager_id", "managerId",
            "office_code", "officeCode", "shift", "department", "designation"
    );

    public static final Set<String> TRANSPORT_OWNED_FIELDS = Set.of(
            "pickup_point", "pickupPoint",
            "special_assistance_needs", "specialAssistanceNeeds",
            "drop_point", "dropPoint",
            "preferred_cab_type", "preferredCabType"
    );

    /**
     * Sanitizes HR data by stripping any Transport-owned fields.
     * Transport fields supplied in HR payloads are silently ignored per Case Study §II.
     */
    public void sanitizeHrPayload(EmployeeDataDto data) {
        if (data == null) return;
        data.setPickupPoint(null);
        data.setSpecialAssistanceNeeds(null);
        data.setDropPoint(null);
        data.setPreferredCabType(null);
    }

    /**
     * Updates transport fields on the employee record.
     * Guaranteed to touch ONLY Transport-owned fields.
     */
    public void applyTransportFields(Employee employee, TransportFieldUpdateRequest request) {
        if (request == null || employee == null) return;

        if (request.getPickupPoint() != null) {
            employee.setPickupPoint(request.getPickupPoint().trim());
        }
        if (request.getSpecialAssistanceNeeds() != null) {
            employee.setSpecialAssistanceNeeds(request.getSpecialAssistanceNeeds().trim());
        }
        if (request.getDropPoint() != null) {
            employee.setDropPoint(request.getDropPoint().trim());
        }
        if (request.getPreferredCabType() != null) {
            employee.setPreferredCabType(request.getPreferredCabType().trim());
        }
    }

    /**
     * Rejects attempts by Transport Admins to modify HR-owned fields.
     */
    public void rejectIfContainsHrFields(Set<String> fieldNames) {
        if (fieldNames == null) return;
        for (String field : fieldNames) {
            if (HR_OWNED_FIELDS.contains(field)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Transport Admin cannot modify HR-owned field: " + field
                );
            }
        }
    }
}

