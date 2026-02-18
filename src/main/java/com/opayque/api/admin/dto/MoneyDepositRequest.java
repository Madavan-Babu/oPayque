package com.opayque.api.admin.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/**
 * Immutable payload for Admin-initiated fund injection ("Minting").
 */
public record MoneyDepositRequest(

        @NotNull(message = "Amount is required")
        @Positive(message = "Deposit amount must be greater than zero")
        @Digits(integer = 10, fraction = 2, message = "Amount cannot exceed 10 digits with 2 decimal places")
        BigDecimal amount,

        @NotBlank(message = "Currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "Currency must be a valid 3-letter ISO code (e.g., EUR, GBP)")
        String currency,

        @Size(max = 100, message = "Description cannot exceed 100 characters")
        String description
) { }