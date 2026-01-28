package com.opayque.api.identity.dto;

import com.opayque.api.infrastructure.util.Masked;
import java.util.UUID;

/// Epic 1: Identity & Access Management - Egress Data Schema.
///
/// Represents the sanitized response returned upon successful user registration.
/// This record enforces the "Opaque" principle by explicitly excluding internal
/// security artifacts like password hashes or system metadata.
///
/// @param id The unique internal identifier for the new user account.
/// @param email The registered identity, protected via @Masked for API egress.
/// @param fullName The account holder's name, protected via @Masked.
/// @param role The RBAC level assigned to the identity.
public record RegisterResponse(
        UUID id,
        @Masked
        String email,
        @Masked
        String fullName,
        String role
) {}