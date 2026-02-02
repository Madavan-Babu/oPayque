package com.opayque.api.wallet.repository;

import com.opayque.api.wallet.entity.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/// Data access abstraction for Multi-Currency Account management.
///
/// This repository facilitates the persistence and retrieval of [Account] entities.
/// It acts as a critical guardrail for the "High-Precision" core by enforcing uniqueness constraints
/// and providing atomic access to the underlying PostgreSQL sequences for banking identifier generation.
///
/// **Architectural Standards:**
/// - **Concurrency Control:** Utilizes Pessimistic Locking to ensure ACID-compliant money movement.
/// - **Identifier Integrity:** Interfaces with native sequences to generate non-colliding account numbers.
/// - **Constraint Enforcement:** Supports business logic to prevent duplicate sub-wallets per currency.
public interface AccountRepository extends JpaRepository<Account, UUID> {

    /// Checks for the existence of a wallet linked to a specific user and currency.
    ///
    /// This method is utilized to enforce the business invariant that a single user cannot
    /// own multiple accounts of the same ISO 4217 currency (e.g., two separate USD wallets).
    ///
    /// @param userId The unique identifier of the user identity.
    /// @param currencyCode The 3-character ISO 4217 currency code.
    /// @return true if an account already exists; false otherwise.
    boolean existsByUserIdAndCurrencyCode(UUID userId, String currencyCode);

    /// Retrieves a unique value from the primary account number sequence.
    ///
    /// This method performs a native query to fetch the next incremental value from `account_number_seq`.
    /// The resulting value is the numerical foundation for constructing ISO 13616 compliant IBANs,
    /// ensuring zero collisions within the global banking ecosystem.
    ///
    /// @return The next available [Long] value from the PostgreSQL sequence.
    @Query(value = "SELECT nextval('account_number_seq')", nativeQuery = true)
    Long getNextAccountNumber();

    /// Retrieves an [Account] by its primary key with an exclusive database-level lock.
    ///
    /// By overriding the standard implementation with `LockModeType.PESSIMISTIC_WRITE`, this method
    /// triggers a `SELECT ... FOR UPDATE` command.
    ///
    /// **Concurrency Impact:**
    /// Any competing transaction (Thread B) attempting to lock the same row will be suspended by
    /// the database until this transaction (Thread A) commits or rolls back.
    /// This eliminates "Lost Updates" and race conditions during high-frequency fund transfers.
    ///
    /// @param id The unique identifier of the account.
    /// @return An [Optional] containing the account, locked for exclusive modification.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Override
    Optional<Account> findById(UUID id);

    /// Explicit fetcher for account state with pessimistic write locking.
    ///
    ///     ///
    /// This query-based implementation ensures that the [Account] state is synchronized with the
    /// database and protected from concurrent modifications during the "Withdraw" and "Deposit"
    /// phases of the Transfer Engine.
    ///
    /// @param id The account UUID to retrieve and lock.
    /// @return An [Optional] account entity, ensuring thread-safety at the row level.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);
}