package com.opayque.api.identity.controller;

import com.opayque.api.identity.dto.LoginRequest;
import com.opayque.api.identity.dto.LoginResponse;
import com.opayque.api.identity.dto.RefreshTokenRequest;
import com.opayque.api.identity.dto.RegisterRequest;
import com.opayque.api.identity.dto.RegisterResponse;
import com.opayque.api.identity.service.AuthService;
import com.opayque.api.infrastructure.util.SecurityUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/// Epic 1: Identity & Access Management - Authentication API.
///
/// This controller provides the primary endpoints for user lifecycle management,
/// including registration, credential verification, token rotation, and secure logout.
/// It enforces the "Opaque Security" principle by strictly using DTOs and
/// delegating business logic to the service layer.
///
/// Governance: Adheres to OWASP API Security principles for broken authentication
/// and session management.
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /// Registers a new user identity in the oPayque ecosystem.
    ///
    /// Validates the input payload and delegates the creation to the [AuthService].
    ///
    /// @param request The validated registration payload.
    /// @return A 201 Created status with the {@link RegisterResponse}.
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@RequestBody @Valid RegisterRequest request) {
        return new ResponseEntity<>(authService.register(request), HttpStatus.CREATED);
    }

    /// Authenticates user credentials and issues a Dual Token Pair (Access + Refresh).
    ///
    /// Performs credential verification via the [AuthService] and returns a signed
    /// response containing both temporal access and persistent refresh tokens.
    ///
    /// @param request The validated login credentials.
    /// @return A 200 OK status with the signed {@link LoginResponse} containing both tokens.
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> authenticate(@RequestBody @Valid LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /// STORY 1.6: Secure Token Rotation Endpoint.
    ///
    /// Exchanges a valid Refresh Token for a brand new Access and Refresh Token pair.
    /// This implementation utilizes "Token Rotation" - once used, the old refresh token
    /// is immediately invalidated to mitigate the risk of token theft.
    ///
    /// @param request Contains the opaque refresh token string.
    /// @return A 200 OK with the NEW token pair.
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refreshToken(@RequestBody @Valid RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refreshToken(request));
    }

    /// Terminates the user's current session by invalidating the provided JWT.
    ///
    /// Implements a manual "Kill Switch" by adding the current token to the Redis blocklist.
    /// Setting `required = false` on the header prevents framework-level errors,
    /// allowing for a semantically correct 401 response for malformed headers.
    ///
    /// @param authHeader The raw Authorization header containing the Bearer token.
    /// @return 200 OK on success, or 401 Unauthorized if the header is missing/invalid.
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader
    ) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            authService.logout(token);

            // Finalizes session termination by clearing thread-local security context.
            SecurityUtil.clear();
            return ResponseEntity.ok().build();
        }

        // Fulfills security protocols by rejecting missing or malformed headers.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}