package com.opayque.api.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/// Epic 1: Identity & Access Management - Authentication Request
///
/// This record defines the credentials required for stateless user authentication.
/// It utilizes Jakarta Bean Validation to enforce strict data entry standards before
/// the payload reaches the security filters.
///
/// @param email The unique identifier for the account; must adhere to RFC 5322 standards.
/// @param password The raw password payload; required to verify the identity against the ledger.
public record LoginRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        String email,

        @NotBlank(message = "Password is required")
        String password
) {}