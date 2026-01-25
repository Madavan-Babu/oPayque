package com.opayque.api.identity.controller;

import com.opayque.api.identity.dto.LoginRequest;
import com.opayque.api.identity.dto.LoginResponse;
import com.opayque.api.identity.dto.RegisterRequest;
import com.opayque.api.identity.dto.RegisterResponse;
import com.opayque.api.identity.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// Epic 1: Identity & Access Management - Authentication Gateway
///
/// This controller serves as the primary entry point for user onboarding and credential issuance.
/// It adheres to a strict layered architecture, ensuring that the web layer only handles request
/// mapping, logging, and validation, while all business logic is encapsulated within the
/// service layer.
///
/// ### Design Principles:
/// - **Validation-First**: Employs `@Valid` to reject malformed payloads before they reach the core logic.
/// - **Opaque Responses**: Returns sanitized DTOs to prevent leaking internal database structures.
/// - **Stateless Execution**: Supports the scale-out requirements of our AWS-hosted infrastructure.
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /// Handles incoming registration requests for new users.
    ///
    /// The `@Valid` annotation ensures that fields such as email and password meet the
    /// required constraints defined in the {@link RegisterRequest}.
    ///
    /// @param request The validated user registration data.
    /// @return A {@link RegisterResponse} containing non-sensitive account metadata with a 201 Created status.
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        log.info("REST request to register new user identity: {}", request.email());

        // Delegation to AuthService ensures password hashing and atomic persistence
        RegisterResponse response = authService.register(request);

        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    /// Authenticates existing users and issues stateless JWT credentials.
    ///
    /// Validates the provided {@link LoginRequest} against the encrypted ledger. Upon
    /// success, it returns a Bearer token for authorized access to core banking functions.
    ///
    /// @param request The validated login credentials (email and password).
    /// @return A {@link LoginResponse} containing the signed JWT and authentication type.
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("REST request to authenticate user: {}", request.email());

        // Triggers the internal Spring Security authentication flow and token generation
        LoginResponse response = authService.login(request);

        return ResponseEntity.ok(response);
    }
}