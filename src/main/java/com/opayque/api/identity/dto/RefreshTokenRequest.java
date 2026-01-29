package com.opayque.api.identity.dto;

import jakarta.validation.constraints.NotBlank;

/// Epic 1: Identity & Access Management - Token Rotation Schema.
///
/// Encapsulates the required data for rotating an expired access token using
/// a persistent refresh token.
///
/// @param refreshToken The opaque refresh token string to be exchanged.
public record RefreshTokenRequest(
        @NotBlank(message = "Refresh token is required")
        String refreshToken
) {}