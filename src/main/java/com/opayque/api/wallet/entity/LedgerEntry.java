package com.opayque.api.wallet.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.proxy.HibernateProxy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
@Immutable // Performance: Disables dirty-checking for append-only data
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    @ToString.Exclude // Critical: Prevent StackOverflow in logs
    private Account account;

    // The final amount affecting the wallet (e.g., 85.00 EUR)
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    // --- Story 2.3: Multi-Currency Audit Trail ---

    // FIX: Map the 'direction' column to satisfy the NOT NULL constraint.
    // We will autopopulate this in the Service layer based on TransactionType.
    @Column(nullable = false, length = 10)
    private String direction;

    @Column(name = "original_amount", precision = 19, scale = 4)
    private BigDecimal originalAmount;

    @Column(name = "original_currency", length = 3)
    private String originalCurrency;

    @Column(name = "exchange_rate", precision = 19, scale = 6)
    private BigDecimal exchangeRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private TransactionType transactionType;

    @Column(nullable = false)
    private String description;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    // --- EFFECTIVE JAVA: JPA COMPLIANT EQUALS & HASHCODE ---
    // We only check equality based on ID (Primary Key).
    // If ID is null (new entity), it is not equal to anything else.

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