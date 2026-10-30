package com.moveinsync.roster.validation;

import com.moveinsync.roster.domain.dto.SyncEventRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DeactivateEventValidator {

    public ValidationResult validate(SyncEventRequest request) {
        List<String> errors = new ArrayList<>();

        if (request.getEventId() == null || request.getEventId().isBlank()) {
            errors.add("Event ID is required");
        }
        if (request.getTenantId() == null || request.getTenantId().isBlank()) {
            errors.add("Tenant ID is required");
        }
        if (request.getExternalEmployeeId() == null || request.getExternalEmployeeId().isBlank()) {
            errors.add("Invalid employee ID");
        } else if (request.getExternalEmployeeId().length() > 64) {
            errors.add("Employee ID exceeds 64 characters");
        }
        if (request.getSourceVersion() == null || request.getSourceVersion() <= 0) {
            errors.add("Invalid version");
        }

        // DEACTIVATE must NOT carry a data payload
        if (request.getData() != null) {
            errors.add("DEACTIVATE must not carry a data payload");
        }

        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.fail(errors);
    }
}

