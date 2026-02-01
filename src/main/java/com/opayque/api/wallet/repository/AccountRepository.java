package com.opayque.api.wallet.repository;

import com.opayque.api.wallet.entity.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/// Multi-Currency Account Management - Persistence Abstraction.
///
/// Provides the data access layer for [Account] entities.
/// Enforces uniqueness constraints and facilitates atomic sequence retrieval
/// for financial identifier generation.
public interface AccountRepository extends JpaRepository<Account, UUID> {

    /// Verifies the existence of a wallet for a specific user and currency.
    ///
    /// Used to enforce business rules preventing duplicate sub-wallets
    /// for the same ISO 4217 currency.
    ///
    /// @param userId The unique identifier of the user identity.
    /// @param currencyCode The ISO 4217 currency code.
    /// @return true if an account exists; false otherwise.
    boolean existsByUserIdAndCurrencyCode(UUID userId, String currencyCode);

    /// Atomic Sequence Fetcher.
    ///
    /// Directly interfaces with the underlying PostgreSQL sequence to retrieve
    /// a guaranteed unique, non-colliding numeric identifier.
    /// This value is critical for ISO 13616 compliant IBAN construction to prevent
    /// routing collisions.
    ///
    /// @return The next incremental value from 'account_number_seq'.
    @Query(value = "SELECT nextval('account_number_seq')", nativeQuery = true)
    Long getNextAccountNumber();

    // FIX: Override the standard findById with a PESSIMISTIC_WRITE lock.
    // This issues a "SELECT ... FOR UPDATE" SQL command.
    // Result: Thread B waits at the DB level until Thread A commits.
    // No more OptimisticLockingFailureException on the parent User.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Override
    Optional<Account> findById(UUID id);

    //NEW FIX FOR LedgerStressTest
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);
}