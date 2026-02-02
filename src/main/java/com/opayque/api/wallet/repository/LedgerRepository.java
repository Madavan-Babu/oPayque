package com.opayque.api.wallet.repository;

import com.opayque.api.wallet.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/// Data access layer for the immutable transaction ledger.
///
/// This repository facilitates the management of [LedgerEntry] entities, which represent
/// the single source of truth for all financial movements within the oPayque ecosystem.
///
/// **Architectural Role:**
/// - **State Derivation:** Responsible for calculating real-time balances by aggregating
///   immutable ledger records.
/// - **Audit Integrity:** Interacts with a partitioned PostgreSQL table designed for high-volume
///   financial auditing and history.
@Repository
public interface LedgerRepository extends JpaRepository<LedgerEntry, UUID> {

    /// Calculates the current available balance for a specific account using a zero-sum aggregation.
    ///
    /// This query implements the core "Dynamic Balance Aggregation" logic.
    /// Instead of relying on a mutable balance field in the account table, it derives
    /// the balance by summing all historical [LedgerEntry] records associated with
    /// the account.
    ///
    /// **Mathematical Logic:**
    /// - **CREDIT**: Positive contribution to the sum (Money IN).
    /// - **DEBIT**: Negative contribution to the sum (Money OUT).
    /// - **COALESCE**: Ensures a result of 0.00 is returned for new accounts with no entries.
    ///
    /// **Implementation Note:**
    /// Utilizes the Fully Qualified Enum Name (FQEN) for `TransactionType.CREDIT` to ensure
    /// JPQL semantic validation succeeds during the Spring Boot startup phase.
    ///
    /// @param accountId The unique identifier (UUID) of the target `Account`.
    /// @return The real-time balance as a [java.math.BigDecimal] with a default of zero.
    @Query("""
        SELECT COALESCE(SUM(
            CASE 
                WHEN l.transactionType = com.opayque.api.wallet.entity.TransactionType.CREDIT 
                THEN l.amount 
                ELSE -l.amount 
            END
        ), 0) 
        FROM LedgerEntry l 
        WHERE l.account.id = :accountId
    """)
    java.math.BigDecimal getBalance(UUID accountId);
}