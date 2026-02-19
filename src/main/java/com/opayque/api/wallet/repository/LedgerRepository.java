package com.opayque.api.wallet.repository;

import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.LedgerEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
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

    /// Retrieves a list of ledger entries associated with the specified account.
    /// This method provides the transaction history for an account, which can be used
    /// to verify atomic transfers and analyze account activity.
    ///
    /// @param account The account for which the ledger entries are to be retrieved.
    ///                Must be a valid non-null Account object.
    /// @return A list of `LedgerEntry` objects associated with the specified account.
    ///         The returned list may be empty if no transactions are found for the account.
    List<LedgerEntry> findByAccount(Account account);

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

    /**
     * Epic 5.2: Retrieves a forward-only slice of ledger entries for high-performance CSV streaming.
     * <p>
     * Utilizes {@link org.springframework.data.domain.Slice} instead of {@code Page} to avoid
     * the expensive {@code COUNT()} query on heavily populated ledger partitions.
     * The query relies on the {@code idx_ledger_account_date} index for rapid chronological sorting.
     *
     * @param accountId  The unique identifier of the wallet account.
     * @param startDate  The inclusive start boundary.
     * @param endDate    The inclusive end boundary.
     * @param pageable   Pagination instructions (Limit and Offset).
     * @return A slice containing the requested entries and a flag indicating if more data exists.
     */
    Slice<LedgerEntry> findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(
            UUID accountId,
            LocalDateTime startDate,
            LocalDateTime endDate,
            Pageable pageable
    );
}