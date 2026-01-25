package com.opayque.api.infrastructure.exception;

import java.time.LocalDateTime;

/// oPayque Standardized Error Schema
/// * A record-based DTO that defines the consistent JSON format for all API errors.
/// This ensures that frontend integrations (React/Flutter) can handle failures
/// predictably using a single error-handling logic.
/// * @param status The HTTP status code (e.g., 404, 401, 500).
/// @param code A machine-readable string code for specific error categorization.
/// @param message A human-readable description of the failure.
/// @param path The specific API endpoint that triggered the exception.
/// @param timestamp The ISO-8601 timestamp of when the error occurred for audit tracing.
public record ErrorResponse(
        int status,
        String code,
        String message,
        String path,
        LocalDateTime timestamp
) {
    /// Secondary constructor that automatically injects the current system time.
    /// * This ensures that every error response includes a high-precision timestamp
    /// required for "Bank-Grade" auditing and CloudWatch log correlation.
    ///
    /// @param status HTTP Status code.
    /// @param code Internal error code.
    /// @param message Description of the error.
    /// @param path The URI path of the request.
    public ErrorResponse(int status, String code, String message, String path) {
        this(status, code, message, path, LocalDateTime.now());
    }
}