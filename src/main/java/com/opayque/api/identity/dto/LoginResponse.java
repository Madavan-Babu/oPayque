package com.opayque.api.identity.dto;

/// Epic 1: Identity & Access Management - Authentication Response Schema.
///
/// An immutable representation of the security tokens issued upon successful
/// authentication or rotation.
///
/// @param token The short-lived JWT access token for resource authorization.
/// @param refreshToken The long-lived opaque token used for rotating access tokens.
/// @param type The token schema (e.g., "Bearer").
public record LoginResponse(
        String token,
        String refreshToken,
        String type
) {
    /// Canonical constructor for standard Bearer token responses.
    public LoginResponse(String token, String refreshToken) {
        this(token, refreshToken, "Bearer");
    }
}