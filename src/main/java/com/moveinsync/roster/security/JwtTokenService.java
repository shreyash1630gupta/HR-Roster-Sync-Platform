package com.moveinsync.roster.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.domain.enums.UserRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service for issuing and validating RFC 7519 HMAC-SHA256 JWT tokens.
 * Extracts authenticated tenant_id, subject, and UserRole claims.
 */
@Slf4j
@Service
public class JwtTokenService {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String JWT_HEADER_B64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] secretKeyBytes;
    private final ObjectMapper objectMapper;

    public JwtTokenService(
            @Value("${roster.jwt.secret:MoveInSyncRosterSyncServiceSecretKeyMin256Bits2026}") String secretKey,
            ObjectMapper objectMapper
    ) {
        this.secretKeyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    /**
     * Generates a signed HS256 JWT token for the specified tenant, role, and subject.
     */
    public String generateToken(UUID tenantId, UserRole role, String subject, Duration validity) {
        long nowSeconds = Instant.now().getEpochSecond();
        long expSeconds = nowSeconds + validity.getSeconds();

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("sub", subject != null ? subject : "service-account");
        payloadMap.put("tenant_id", tenantId.toString());
        payloadMap.put("role", role.name());
        payloadMap.put("iat", nowSeconds);
        payloadMap.put("exp", expSeconds);

        try {
            String payloadJson = objectMapper.writeValueAsString(payloadMap);
            String payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

            String dataToSign = JWT_HEADER_B64 + "." + payloadB64;
            String signatureB64 = signHmacSha256(dataToSign);

            return dataToSign + "." + signatureB64;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate JWT token: " + ex.getMessage(), ex);
        }
    }

    /**
     * Parses and validates a JWT token.
     *
     * @param token the raw JWT token string
     * @return the extracted TenantContext if valid
     * @throws SecurityException if signature is invalid, token is expired, or format is malformed
     */
    public TenantContext validateAndParse(String token) {
        if (token == null || token.isBlank()) {
            throw new SecurityException("JWT token is empty or null");
        }

        String[] parts = token.trim().split("\\.");
        if (parts.length != 3) {
            throw new SecurityException("Invalid JWT format: expected 3 dot-separated parts");
        }

        String headerB64 = parts[0];
        String payloadB64 = parts[1];
        String signatureB64 = parts[2];

        // 1. Validate signature
        String dataToSign = headerB64 + "." + payloadB64;
        String expectedSignatureB64 = signHmacSha256(dataToSign);

        if (!MessageDigest.isEqual(
                signatureB64.getBytes(StandardCharsets.UTF_8),
                expectedSignatureB64.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new SecurityException("Invalid JWT signature");
        }

        // 2. Parse payload claims
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(payloadB64);
            JsonNode root = objectMapper.readTree(payloadBytes);

            // Check expiration
            JsonNode expNode = root.get("exp");
            if (expNode == null || !expNode.canConvertToLong()) {
                throw new SecurityException("Missing or invalid exp claim in JWT");
            }
            long expSeconds = expNode.asLong();
            if (expSeconds <= Instant.now().getEpochSecond()) {
                throw new SecurityException("JWT token has expired");
            }

            // Extract claims
            String tenantIdStr = root.hasNonNull("tenant_id") ? root.get("tenant_id").asText() : null;
            if (tenantIdStr == null || tenantIdStr.isBlank()) {
                throw new SecurityException("Missing tenant_id claim in JWT");
            }
            UUID tenantId = UUID.fromString(tenantIdStr);

            String roleStr = root.hasNonNull("role") ? root.get("role").asText() : null;
            if (roleStr == null || roleStr.isBlank()) {
                throw new SecurityException("Missing role claim in JWT");
            }
            UserRole role = UserRole.valueOf(roleStr.toUpperCase());

            String sub = root.hasNonNull("sub") ? root.get("sub").asText() : "anonymous";

            return TenantContext.builder()
                    .tenantId(tenantId)
                    .userId(sub)
                    .role(role)
                    .build();
        } catch (IllegalArgumentException iae) {
            throw new SecurityException("Malformed UUID or role in JWT payload: " + iae.getMessage(), iae);
        } catch (SecurityException se) {
            throw se;
        } catch (Exception ex) {
            throw new SecurityException("Failed to parse JWT payload: " + ex.getMessage(), ex);
        }
    }

    private String signHmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secretKeyBytes, HMAC_SHA256));
            byte[] signature = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256: " + ex.getMessage(), ex);
        }
    }
}

