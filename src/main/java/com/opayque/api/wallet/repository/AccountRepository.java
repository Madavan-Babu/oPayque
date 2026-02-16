package com.opayque.api.wallet.repository;

import com.opayque.api.wallet.entity.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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


    /// Retrieves a list of all accounts associated with a specific user.
    /// This method is used to fetch all multi-currency accounts linked to
    /// a user, identified by their unique ID.
    ///
    /// @param userId The unique identifier of the user whose accounts are to be retrieved.
    /// @return A list of `Account` entities belonging to the specified user.
    List<Account> findAllByUserId(UUID userId);

    /// Retrieves a unique value from the primary account number sequence.
    ///
    /// This method performs a native query to fetch the next incremental value from `account_number_seq`.
    /// The resulting value is the numerical foundation for constructing ISO 13616 compliant IBANs,
    /// ensuring zero collisions within the global banking ecosystem.
    ///
    /// @return The next available [Long] value from the PostgreSQL sequence.
    @Query(value = "SELECT nextval('account_number_seq')", nativeQuery = true)
    Long getNextAccountNumber();

  /// Retrieves an immutable snapshot of an [Account] by its globally-unique identifier.
  ///
  /// This method is invoked by the Transfer Engine, Ledger Reconciliation, and Regulatory Reporting
  /// sub-systems to obtain a read-only view of the account state. It guarantees that the returned
  /// entity reflects the last committed transaction on the primary PostgreSQL node, ensuring
  /// monotonic consistency for downstream AML, charge-back, and FX settlement workflows.
  ///
  /// **Security Considerations:**
  /// - The returned `Optional` is empty when the UUID is not found, preventing NPE-based denial
  ///   of service vectors that could be exploited to probe valid account ranges.
  /// - No row-level lock is acquired; callers requiring serialised money movement must use
  ///   `findByIdForUpdate` to enforce pessimistic concurrency control.
  ///
  /// **Compliance Mapping:**
  /// - Satisfies PCI-DSS Req. 7.1.2 – least-privilege access by exposing only non-sensitive
  ///   account metadata (no PAN, CVV, or PCI-protected fields).
  /// - Aligns with ISO 20022 pain.001 message specifications for account reference resolution.
  ///
  /// @param id The UUIDv4 primary key of the account.
  /// @return An [Optional] containing the [Account] entity, or empty if no account exists.
    @Override
    Optional<Account> findById(UUID id);

    /// Explicit fetcher for account state with pessimistic write locking.
    ///
    ///
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