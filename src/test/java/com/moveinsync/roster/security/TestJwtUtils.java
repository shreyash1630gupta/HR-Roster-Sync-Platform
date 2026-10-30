package com.moveinsync.roster.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.domain.enums.UserRole;

import java.time.Duration;
import java.util.UUID;

/**
 * Test helper for generating signed JWT tokens for various tenants and roles.
 */
public class TestJwtUtils {

    public static final String TEST_SECRET = "MoveInSyncRosterSyncServiceSecretKeyMin256Bits2026";

    private static final JwtTokenService JWT_SERVICE = new JwtTokenService(
            TEST_SECRET,
            new ObjectMapper()
    );

    public static String createToken(UUID tenantId, UserRole role) {
        return JWT_SERVICE.generateToken(tenantId, role, "test-user", Duration.ofHours(1));
    }

    public static String createExpiredToken(UUID tenantId, UserRole role) {
        return JWT_SERVICE.generateToken(tenantId, role, "test-user", Duration.ofSeconds(-60));
    }
}

