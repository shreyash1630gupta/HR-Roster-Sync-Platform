package com.moveinsync.roster.controller;

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
    public ResponseEntity<TokenResponse> issueToken(@RequestBody TokenRequest request) {
        UUID tenantId = syncEventService.resolveTenantId(request.getTenantId());
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
        private String tenantId;
        private UserRole role;
        private String subject;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TokenResponse {
        private String token;
        private UUID tenantId;
        private UserRole role;
        private long expiresInSeconds;
    }
}

