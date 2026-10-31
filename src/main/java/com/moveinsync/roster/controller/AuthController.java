package com.moveinsync.roster.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.moveinsync.roster.domain.enums.UserRole;
import com.moveinsync.roster.security.JwtTokenService;
import com.moveinsync.roster.service.SyncEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Endpoints for generating test and client JWT bearer tokens")
public class AuthController {

    private final JwtTokenService jwtTokenService;
    private final SyncEventService syncEventService;

    @PostMapping("/token")
    @Operation(summary = "Generate JWT Token", description = "Issues a signed JWT token with specified tenant and role claims")
    public ResponseEntity<TokenResponse> issueToken(@RequestBody(required = false) TokenRequest request) {
        if (request == null) {
            request = new TokenRequest();
        }
        String tenantInput = (request.getTenantId() != null && !request.getTenantId().isBlank())
                ? request.getTenantId()
                : "11111111-1111-1111-1111-111111111111";

        UUID tenantId = syncEventService.resolveTenantId(tenantInput);
        UserRole role = request.getRole() != null ? request.getRole() : UserRole.HR_SYSTEM;
        String subject = request.getSubject() != null ? request.getSubject() : "api-client";
        Duration validity = Duration.ofHours(24);

        String token = jwtTokenService.generateToken(tenantId, role, subject, validity);

        return ResponseEntity.ok(TokenResponse.builder()
                .token(token)
                .tenantId(tenantId)
                .role(role)
                .expiresInSeconds(validity.getSeconds())
                .build());
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TokenRequest {
        @JsonProperty("tenant_id")
        @JsonAlias({"tenant_id", "tenantId"})
        private String tenantId;

        @JsonProperty("role")
        private UserRole role;

        @JsonProperty("subject")
        private String subject;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TokenResponse {
        @JsonProperty("token")
        private String token;

        @JsonProperty("tenant_id")
        @JsonAlias({"tenant_id", "tenantId"})
        private UUID tenantId;

        @JsonProperty("role")
        private UserRole role;

        @JsonProperty("expires_in_seconds")
        @JsonAlias({"expires_in_seconds", "expiresInSeconds"})
        private long expiresInSeconds;
    }
}

