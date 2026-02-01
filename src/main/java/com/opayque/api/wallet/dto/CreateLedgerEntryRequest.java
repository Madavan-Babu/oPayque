package com.opayque.api.wallet.dto;

import com.opayque.api.wallet.entity.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record CreateLedgerEntryRequest(
        @NotNull UUID accountId,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency, // e.g., USD
        @NotNull TransactionType type,
        String description,

        // Optional: Only used to test security filters.
        // The service should IGNORE this and use System.now()
        LocalDateTime timestamp
) {}