package com.opayque.api.wallet.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.proxy.HibernateProxy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/// Represents a single entry in the financial ledger for an account.
/// This entity is immutable and serves as the core persistence model for
/// tracking account transactions in the system. Each ledger entry impacts
/// the balance of its associated account and is recorded with metadata
/// for auditing and reconciliation purposes.
/// Annotations:
/// - `@Entity`: Specifies that the class is a JPA entity.
/// - `@Table`: Maps this entity to the database table "ledger_entries".
/// - `@Immutable`: Indicates that the entity is read-only and should not
///   be modified after creation.
/// - Indexes:
///   - `idx_ledger_covering`: A covering index for the columns
///     "account_id", "transaction_type", and "amount". This index is optimized
///     for aggregation queries.
///   - `idx_ledger_recorded_at`: Index on the "recorded_at" column,
///     used for time-based partitions or queries.
/// Fields:
/// - `id`: Unique identifier for the ledger entry, generated as a UUID.
/// - `account`: The account associated with the entry. Established as a
///   many-to-one relationship with Lazy loading, to optimize bulk fetches.
/// - `amount`: Normalized amount affecting the account balance, expressed
///   in the account's base currency. Uses high precision for financial calculations.
/// - `currency`: ISO 4217 currency code corresponding to the amount field.
/// - `direction`: Indicates the flow of funds (e.g., CREDIT or DEBIT).
/// - `originalAmount`: The pre-conversion amount requested.
/// - `originalCurrency`: The currency of the originalAmount prior to conversion.
/// - `exchangeRate`: The conversion rate applied to the originalAmount.
/// - `transactionType`: Categorization of the transaction (e.g., TRANSFER, DEPOSIT).
/// - `description`: Contextual information for the ledger entry, useful for
///   auditing or user-facing purposes.
/// - `recordedAt`: The timestamp marking when the ledger entry was created.
///   Functions as a partitioning key for the database table.
/// Methods:
/// - `equals()`: Implements JPA-compliant equality based strictly on the
///   primary key (UUID). Supports comparison of entities and Hibernate proxies.
/// - `hashCode()`: Generates a hash code based on the primary key and
///   the entity class.
@Entity
@Table(
        name = "ledger_entries",
        indexes = {
                // CHANGED TO COVERING INDEX
                // Includes 'transaction_type' and 'amount' so the aggregation query
                // can run purely in the Index (Index-Only Scan), bypassing the Heap.
                @Index(name = "idx_ledger_covering", columnList = "account_id, transaction_type, amount"),
                @Index(name = "idx_ledger_recorded_at", columnList = "recorded_at")
        }
)
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

    /**
     * Computes the hash code for the current object. This implementation ensures compatibility
     * with Hibernate Lazy Loading and Proxy objects. When the current object (`this`)
     * is a Hibernate proxy, the hash code is derived from the persistent class. Otherwise,
     * it is derived from the actual class of the object.
     *
     * @return an integer representing the hash code based on the persistent class if
     *         the object is a Hibernate proxy, or the object's class otherwise.
     */
    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}