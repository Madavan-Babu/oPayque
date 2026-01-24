package com.opayque.api.identity.controller;

import com.opayque.api.identity.dto.RegisterRequest;
import com.opayque.api.identity.dto.RegisterResponse;
import com.opayque.api.identity.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Epic 1: Identity & Access Management - Story 1.1 User Registration
 * * This controller serves as the primary entry point for user onboarding.
 * It adheres to a strict layered architecture, ensuring that the web layer
 * only handles request mapping and validation, while business logic is
 * encapsulated within the service layer.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * Handles incoming registration requests.
     * * The @Valid annotation ensures that the RegisterRequest fields (email, password, fullName)
     * meet the required constraints before entering the service layer.
     *
     * @param request The validated user registration data.
     * @return A RegisterResponse containing sanitized user details with a 201 Created status.
     */
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        // Delegation to AuthService ensures password hashing and persistence are handled
        // within a transactional boundary.
        return new ResponseEntity<>(authService.register(request), HttpStatus.CREATED);
    }
}