package com.opayque.api.wallet.dto;

import com.opayque.api.infrastructure.util.Masked;
import com.opayque.api.wallet.entity.Account;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/// Multi-Currency Account Management - Egress Data Schema.
///
/// This Data Transfer Object (DTO) represents the public-facing view of a digital wallet.
/// It encapsulates essential account identifiers and the current balance while
/// ensuring sensitive data like the IBAN is protected during serialization.
///
/// Design Pattern: Static Factory Method (fromEntity) for decoupled mapping.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountResponse {

    /// The unique internal identifier for the wallet instance.
    private UUID id;

    /// ISO 4217 Currency Code (e.g., USD, EUR).
    /// Adheres to ISO 20022 compliant naming conventions for global interoperability.
    private String currencyCode;

    /// The International Bank Account Number (IBAN) associated with this wallet.
    /// Annotated with @Masked to trigger automated PII redaction during JSON serialization.
    @Masked
    private String iban;

    /// The current liquid balance of the wallet.
    /// Utilizes BigDecimal to ensure compatibility with high-precision financial operations.
    private BigDecimal balance;

    /// Static mapping utility to transform an [Account] entity into a sanitized [AccountResponse].
    ///
    /// Note: The balance is currently defaulted to zero. Aggregate balance calculations
    /// from the ledger are scheduled for Story 2.3.
    ///
    /// @param account The source entity retrieved from the persistence layer.
    /// @return A mapped and sanitized response DTO.
    public static AccountResponse fromEntity(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getCurrencyCode(),
                account.getIban(),
                BigDecimal.ZERO // Placeholder: Real-time balance aggregation implemented in Story 2.3.
        );
    }
}