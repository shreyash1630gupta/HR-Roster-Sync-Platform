package com.moveinsync.roster.validation;

import com.moveinsync.roster.cache.OfficeCacheService;
import com.moveinsync.roster.domain.dto.SyncEventRequest;
import com.moveinsync.roster.domain.entity.Office;
import com.moveinsync.roster.domain.entity.Tenant;
import com.moveinsync.roster.domain.enums.EventType;
import com.moveinsync.roster.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class EventValidator {

    private final CreateEventValidator createValidator;
    private final UpdateEventValidator updateValidator;
    private final DeactivateEventValidator deactivateValidator;
    private final TenantRepository tenantRepository;
    private final OfficeCacheService officeCacheService;

    public ValidationResult validate(SyncEventRequest request) {
        if (request == null) {
            return ValidationResult.fail("Request body cannot be null");
        }

        if (request.getEventType() == null) {
            return ValidationResult.fail("Event type is required (CREATE, UPDATE, DEACTIVATE)");
        }

        ValidationResult baseResult;
        if (request.getEventType() == EventType.CREATE) {
            baseResult = createValidator.validate(request);
        } else if (request.getEventType() == EventType.UPDATE) {
            baseResult = updateValidator.validate(request);
        } else if (request.getEventType() == EventType.DEACTIVATE) {
            baseResult = deactivateValidator.validate(request);
        } else {
            return ValidationResult.fail("Unsupported event type: " + request.getEventType());
        }

        if (!baseResult.isValid()) {
            return baseResult;
        }

        // Contextual validation: verify tenant and office code
        List<String> contextualErrors = new ArrayList<>();
        UUID tenantUuid = parseUuid(request.getTenantId());
        if (tenantUuid == null) {
            // Check if tenant exists by code
            Optional<Tenant> tenantOpt = tenantRepository.findByCode(request.getTenantId());
            if (tenantOpt.isEmpty()) {
                contextualErrors.add("Unknown tenant");
            } else {
                tenantUuid = tenantOpt.get().getId();
            }
        } else {
            if (!tenantRepository.existsById(tenantUuid)) {
                contextualErrors.add("Unknown tenant");
            }
        }

        // Strict Tenant Isolation: reject request if body tenant_id does not match authenticated jwt tenant_id
        if (com.moveinsync.roster.security.TenantContextHolder.getTenantId() != null && tenantUuid != null) {
            if (!com.moveinsync.roster.security.TenantContextHolder.getTenantId().equals(tenantUuid)) {
                contextualErrors.add("Cross-tenant violation: request tenant does not match authenticated token");
            }
        }

        // Office code validation for CREATE and UPDATE
        if (request.getEventType() != EventType.DEACTIVATE && request.getData() != null) {
            String officeCode = request.getData().getOfficeCode();
            if (officeCode != null && !officeCode.isBlank() && tenantUuid != null) {
                Optional<Office> officeOpt = officeCacheService.getOffice(tenantUuid, officeCode.trim());
                if (officeOpt.isEmpty() || !officeOpt.get().isActive()) {
                    contextualErrors.add("Unknown office code");
                }
            }
        }

        return contextualErrors.isEmpty() ? ValidationResult.ok() : ValidationResult.fail(contextualErrors);
    }

    private UUID parseUuid(String str) {
        if (str == null) return null;
        try {
            return UUID.fromString(str);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

