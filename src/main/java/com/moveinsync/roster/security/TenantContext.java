package com.moveinsync.roster.security;

import com.moveinsync.roster.domain.enums.UserRole;
import lombok.*;

import java.util.UUID;

/**
 * Immutable context object encapsulating the authenticated caller's identity,
 * authoritative tenant boundary, and granted security role.
 */
@Getter
@Builder
@AllArgsConstructor
@ToString
public class TenantContext {

    private final UUID tenantId;
    private final String userId;
    private final UserRole role;

    public boolean hasRole(UserRole requiredRole) {
        if (this.role == UserRole.ADMIN) {
            return true; // ADMIN inherits all permissions
        }
        return this.role == requiredRole;
    }

    public boolean hasAnyRole(UserRole... requiredRoles) {
        if (this.role == UserRole.ADMIN) {
            return true;
        }
        for (UserRole r : requiredRoles) {
            if (this.role == r) {
                return true;
            }
        }
        return false;
    }
}

