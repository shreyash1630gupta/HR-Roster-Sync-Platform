package com.moveinsync.roster.validation;

import com.moveinsync.roster.domain.dto.EmployeeDataDto;
import com.moveinsync.roster.domain.dto.SyncEventRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class UpdateEventValidator {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$"
    );

    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "^\\+?[1-9]\\d{1,14}$"
    );

    public ValidationResult validate(SyncEventRequest request) {
        List<String> errors = new ArrayList<>();

        // Envelope validation
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

        // Full snapshot requirement for V1
        EmployeeDataDto data = request.getData();
        if (data == null) {
            errors.add("Data payload required for UPDATE (full snapshot required)");
            return ValidationResult.fail(errors);
        }

        if (data.getName() == null || data.getName().isBlank()) {
            errors.add("Name is required");
        } else if (data.getName().length() > 200) {
            errors.add("Name exceeds 200 characters");
        }

        if (data.getEmail() == null || data.getEmail().isBlank()) {
            errors.add("Email is required");
        } else if (!EMAIL_PATTERN.matcher(data.getEmail().trim()).matches()) {
            errors.add("Malformed email");
        }

        if (data.getOfficeCode() == null || data.getOfficeCode().isBlank()) {
            errors.add("Office code is required");
        } else if (data.getOfficeCode().length() > 64) {
            errors.add("Office code exceeds 64 characters");
        }

        if (data.getPhone() != null && !data.getPhone().isBlank()) {
            if (!PHONE_PATTERN.matcher(data.getPhone().trim()).matches()) {
                errors.add("Malformed phone number");
            }
        }

        if (data.getManagerId() != null && data.getManagerId().length() > 64) {
            errors.add("Manager ID exceeds 64 characters");
        }

        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.fail(errors);
    }
}

