package com.opayque.api.wallet.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.proxy.HibernateProxy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/// Persistent entity representing an immutable record in the digital ledger.
///
/// This class is the core of the **oPayque** "Magic Ledger". It implements an append-only
/// data pattern where records are never modified once committed, ensuring a tamper-evident
/// audit trail for all financial movements.
///
/// **Architectural Specifications:**
/// - **Persistence Strategy:** Mapped to the `ledger_entries` table which utilizes **PostgreSQL Range
///   Partitioning** (e.g., `ledger_entries_2026`) for high-volume scalability.
/// - **Immutability:** Decorated with Hibernate's `@Immutable` to disable dirty-checking,
///   reducing CPU overhead during transaction commits.
/// - **Story 2.3 Implementation:** Contains extended metadata for cross-currency transfers,
///   capturing the "Financial Truth" of conversions at the exact moment of execution.
@Entity
@Table(name = "ledger_entries")
@Immutable
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerEntry {

    /// Primary Key: Unique identifier for the ledger record.
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /// The associated [Account] (wallet) for this entry.
    /// Utilizes `FetchType.LAZY` to prevent unnecessary database joins during bulk history fetches.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    @ToString.Exclude
    private Account account;

    /// The normalized amount affecting the account balance (expressed in the account's base currency).
    /// Precision is set to (19, 4) to support high-accuracy financial math.
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /// The ISO 4217 currency code for the `amount` field (e.g., "USD", "EUR").
    @Column(nullable = false, length = 3)
    private String currency;

    /// **Story 2.3: Multi-Currency Audit Trail**

    /// Indicates the movement flow: "CREDIT" (inbound, `IN`) or "DEBIT" (outbound, `OUT`).
    /// Used for reconciliation and balance aggregation logic.
    @Column(nullable = false, length = 10)
    private String direction;

    /// The original amount requested before currency conversion was applied.
    @Column(name = "original_amount", precision = 19, scale = 4)
    private BigDecimal originalAmount;

    /// The original currency requested by the user/system before conversion.
    @Column(name = "original_currency", length = 3)
    private String originalCurrency;

    /// The point-in-time exchange rate retrieved from `CurrencyExchangeService`.
    /// Precision (19, 6) allows for granular conversion rates.
    @Column(name = "exchange_rate", precision = 19, scale = 6)
    private BigDecimal exchangeRate;

    /// Categorization of the entry (e.g., TRANSFER, DEPOSIT, WITHDRAWAL).
    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private TransactionType transactionType;

    /// User-defined or system-generated context for the entry.
    @Column(nullable = false)
    private String description;

    /// Atomic timestamp of when the entry was finalized in the ledger.
    /// This field is the partitioning key for the underlying PostgreSQL table.
    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    /// --- EFFECTIVE JAVA: JPA COMPLIANT EQUALS & HASHCODE ---
    /// Identity is strictly bound to the Primary Key (UUID).
    /// This implementation is compatible with Hibernate Lazy Loading and Proxy objects.

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : this.getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        LedgerEntry that = (LedgerEntry) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}