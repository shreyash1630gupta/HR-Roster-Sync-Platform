package com.moveinsync.roster.security;

import java.util.Optional;
import java.util.UUID;

/**
 * ThreadLocal container providing thread-bound access to the authenticated TenantContext.
 * MUST be cleared in a finally block after every request.
 */
public final class TenantContextHolder {

    private static final ThreadLocal<TenantContext> CONTEXT = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    public static void setContext(TenantContext context) {
        CONTEXT.set(context);
    }

    public static TenantContext getContext() {
        return CONTEXT.get();
    }

    public static Optional<TenantContext> getContextOptional() {
        return Optional.ofNullable(CONTEXT.get());
    }

    public static UUID getTenantId() {
        TenantContext ctx = CONTEXT.get();
        return ctx != null ? ctx.getTenantId() : null;
    }

    public static void clear() {
        CONTEXT.remove();
    }
}

