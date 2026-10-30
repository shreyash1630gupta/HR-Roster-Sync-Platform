package com.moveinsync.roster.cache;

import lombok.*;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Tenant-scoped composite key for office validation caching.
 * Prevents cross-tenant cache collisions.
 */
@Getter
@AllArgsConstructor
@ToString
public class TenantOfficeKey implements Serializable {

    private final UUID tenantId;
    private final String officeCode;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TenantOfficeKey that)) return false;
        return Objects.equals(tenantId, that.tenantId) &&
               Objects.equals(officeCode != null ? officeCode.trim().toUpperCase() : null,
                              that.officeCode != null ? that.officeCode.trim().toUpperCase() : null);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, officeCode != null ? officeCode.trim().toUpperCase() : null);
    }
}

