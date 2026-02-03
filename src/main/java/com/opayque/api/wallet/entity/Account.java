package com.opayque.api.wallet.entity;

import com.opayque.api.identity.entity.User;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

/// Multi-Currency Account Management - Persistence Layer.
///
/// Represents a unique digital wallet within the oPayque ledger.
/// This entity maps the physical relationship between users and their currency-specific
/// accounts, enforcing data integrity through strict schema constraints.
///
/// Constraint: One user may possess multiple wallets, but each must be for a
/// distinct currency code (SAS Policy for Wallets).
@Entity
@Table(name = "accounts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Account {

    /// Primary Key: Globally unique identifier for the account instance.
    @Id
    @GeneratedValue
    private UUID id;

    /// The identity owning this wallet.
    /// Utilizes Lazy fetching to optimize memory during high-volume ledger queries.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /// ISO 4217 Currency Code (e.g., USD, EUR).
    /// Mapped to the 'currency' column in the PostgreSQL RDS instance.
    /// Standardized under ISO 20022 naming conventions.
    @Column(name = "currency", nullable = false, length = 3)
    private String currencyCode;

    /// The unique International Bank Account Number (IBAN).
    /// Adheres to ISO 13616 standards with a maximum length of 34 characters.
    @Column(nullable = false, unique = true, length = 34)
    private String iban;
}