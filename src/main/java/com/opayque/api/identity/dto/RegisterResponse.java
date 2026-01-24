package com.opayque.api.identity.dto;

import java.util.UUID;

/**
 * Epic 1: Identity & Access Management - Data Transfer Layer
 * * Represents the sanitized response sent to the client upon a successful user registration.
 * This record ensures that sensitive internal data, such as password hashes or audit timestamps,
 * are never leaked to the frontend.
 * * @param id The unique UUID generated for the new user account.
 * @param email The verified email address associated with the account.
 * @param fullName The legal full name of the account holder.
 * @param role The security role assigned to the user (e.g., CUSTOMER).
 */
public record RegisterResponse(
        UUID id,
        String email,
        String fullName,
        String role
) {}