package com.opayque.api.identity.dto;

import com.opayque.api.identity.entity.Role;
import java.util.UUID;

/// Epic 1: Identity & Access Management - Opaque Data Schema
///
/// A safe, immutable representation of a User identity intended for public API consumption.
/// This DTO strictly excludes sensitive credentials, such as hashed passwords, to maintain
/// banking-grade data privacy standards.
///
/// @param id The unique Universally Unique Identifier (UUID) of the user.
/// @param email The verified email address used as the primary identity.
/// @param fullName The account holder's legal name.
/// @param role The assigned Role-Based Access Control (RBAC) level.
public record UserResponse(
        UUID id,
        String email,
        String fullName,
        Role role
) {}