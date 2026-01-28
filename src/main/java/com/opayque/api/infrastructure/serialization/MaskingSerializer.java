package com.opayque.api.infrastructure.serialization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/// PII Sanitization Engine.
///
/// Implements custom serialization logic to redact sensitive information before
/// API response transmission.
/// Logic is bifurcated into specialized masking for email structures and
/// generic truncation for other identifiers.
public class MaskingSerializer extends JsonSerializer<String> {

    /// Primary serialization entry point.
    /// Performs null-safety checks and routes to appropriate masking logic.
    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }

        // Email-specific masking preserves domain context for client-side routing logic.
        if (value.contains("@")) {
            gen.writeString(maskEmail(value));
        } else {
            gen.writeString(maskGeneric(value));
        }
    }

    /// Masks the local part of an email address.
    /// Format: "d***@domain.com".
    private String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) {
            return "***" + email.substring(atIndex);
        }
        return email.charAt(0) + "***" + email.substring(atIndex);
    }

    /// Generic masking for names, IBANs, and other sensitive identifiers.
    /// Standardizes on a single character prefix followed by redaction.
    private String maskGeneric(String value) {
        if (value.length() <= 1) {
            return "***";
        }
        // Resulting output: "J***" for "John Doe".
        return value.charAt(0) + "***";
    }
}