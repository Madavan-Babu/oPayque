package com.opayque.api.identity.controller;

import com.opayque.api.identity.dto.LoginRequest;
import com.opayque.api.identity.dto.LoginResponse;
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

/// Epic 1: Identity & Access Management - Authentication API
///
/// This controller provides the primary endpoints for user lifecycle management,
/// including registration, credential verification, and secure logout.
/// It enforces the **"Opaque Security"** principle by strictly using DTOs and
/// delegating business logic to the service layer.
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /// Registers a new user identity in the oPayque ecosystem.
    ///
    /// @param request The validated registration payload.
    /// @return A 201 Created status with the {@link RegisterResponse}.
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@RequestBody @Valid RegisterRequest request) {
        return new ResponseEntity<>(authService.register(request), HttpStatus.CREATED);
    }

    /// Authenticates user credentials and issues a stateless JWT.
    ///
    /// @param request The validated login credentials.
    /// @return A 200 OK status with the signed {@link LoginResponse}.
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> authenticate(@RequestBody @Valid LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /// Terminates the user's current session by invalidating the provided JWT.
    ///
    /// This endpoint implements a manual "Kill Switch" by adding the token to the
    /// blocklist. Note that `required = false` on the header allows the controller
    /// to return a semantically correct 401 instead of a framework-level error.
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

            // Clear the thread-local context to finalize logout in the current request
            SecurityUtil.clear();
            return ResponseEntity.ok().build();
        }

        // Return 401 to satisfy security protocols for missing/malformed headers
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}