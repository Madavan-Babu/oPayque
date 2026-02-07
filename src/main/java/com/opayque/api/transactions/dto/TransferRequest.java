package com.opayque.api.transactions.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/// **Story 3.3: The Transfer Contract (Input)**.
///
/// Defines the strict structure for initiating a P2P transfer.
///
/// **Security Design:**
/// - **No Sender ID:** The sender is ALWAYS resolved from the JWT Security Context. We never trust the body.
/// - **String Amount:** Prevents JSON floating-point precision loss (IEEE 754).
/// - **Strict Validation:** Fails fast before business logic is touched.
public record TransferRequest(
        @NotBlank(message = "Receiver email is required")
        @Email(message = "Receiver must be a well-formed email address")
        String receiverEmail,

        @NotBlank(message = "Amount is required")
        @Pattern(regexp = "^\\d+(\\.\\d{1,2})?$", message = "Amount must be a positive number with up to 2 decimal places")
        String amount,

        @NotBlank(message = "Currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO code (e.g., USD)")
        String currency
) {}