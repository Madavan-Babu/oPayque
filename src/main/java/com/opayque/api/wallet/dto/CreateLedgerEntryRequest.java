package com.opayque.api.wallet.dto;

import com.opayque.api.integration.currency.CurrencyExchangeService;
import com.opayque.api.wallet.entity.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/// Data Transfer Object (DTO) for initiating a new entry in the high-precision digital ledger.
///
/// This record serves as the primary input for fund movements, ensuring that all incoming financial data
/// adheres to strict validation rules before reaching the [CurrencyExchangeService] or the
/// atomic transaction engine.
///
/// **Constraints and Standards:**
/// - **Precision:** Uses [BigDecimal] to prevent floating-point rounding errors common in banking.
/// - **ISO Compliance:** Enforces ISO 4217 currency codes via regex patterns.
/// - **Validation:** Employs Bean Validation (JSR 380) to ensure data integrity at the Controller boundary.
///
/// @param accountId The unique identifier (UUID) of the target account.
/// @param amount The financial value of the entry; must be at least 0.01 (one cent).
/// @param currency The three-letter ISO 4217 code (e.g., "USD", "EUR").
/// @param type The classification of the transaction (e.g., DEPOSIT, WITHDRAWAL).
/// @param description An optional metadata string for transaction history/audit logs.
/// @param timestamp An optional field primarily utilized for security filter testing; business logic
///                  must utilize system-generated timestamps for the immutable ledger.
public record CreateLedgerEntryRequest(
        @NotNull
        UUID accountId,

        @NotNull
        @DecimalMin("0.01")
        BigDecimal amount,

        @NotNull
        @Pattern(regexp = "^[A-Z]{3}$")
        String currency,

        @NotNull
        TransactionType type,

        String description,

        // Optional: Only used to test security filters.
        // The service should IGNORE this and use System.now() to ensure an immutable audit trail.
        LocalDateTime timestamp
) {}