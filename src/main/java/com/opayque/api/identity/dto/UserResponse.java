package com.opayque.api.identity.dto;

import com.opayque.api.identity.entity.Role;
import com.opayque.api.infrastructure.util.Masked;
import java.util.UUID;

/// Epic 1: Identity & Access Management - Opaque Identity Representation.
///
/// An immutable data transfer object utilized for public identity consumption.
/// Adheres to banking-grade privacy standards by mandating field-level masking
/// and strictly prohibiting credential exposure.
///
/// @param id The Universally Unique Identifier (UUID) of the user.
/// @param email The primary identity string, sanitized via @Masked.
/// @param fullName The legal name associated with the identity, sanitized via @Masked.
/// @param role The current Role-Based Access Control (RBAC) level.
public record UserResponse(
        UUID id,
        @Masked
        String email,
        @Masked
        String fullName,
        Role role
) {}