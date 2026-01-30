package com.opayque.api.wallet.repository;

import com.opayque.api.wallet.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}