package com.opayque.api.wallet.repository;

import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.LedgerEntry;
import com.opayque.api.wallet.entity.TransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// **Mathematical Parity Audit: SQL vs. Java Aggregation**.
///
/// This suite executes a "Zero-Defect" verification of the [LedgerRepository#getBalance] logic.
/// It validates that the underlying PostgreSQL/H2 `SUM` function accurately aggregates
/// immutable ledger records across varying scales and signs, matching Java's [BigDecimal]
/// math exactly.
///
/// **Epic 2 | Story 2.4 Deliverables:**
/// - **Fuzz Testing:** Generates randomized datasets to detect rounding drifts.
/// - **Contextual Integrity:** Ensures that [TransactionType] based signs (Credit vs. Debit)
///   are correctly interpreted by the database engine.
/// - **Constraint Verification:** Confirms that referential integrity for [User] and [Account]
///   is maintained during bulk entry injection.
///
@DataJpaTest
@ActiveProfiles("test")
class LedgerSqlAggregationTest {

    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    private final Random random = new Random();

    /// **Unit Audit: Single-Entry aggregation**.
    ///
    /// Verifies that the repository's `getBalance` query correctly identifies a single
    /// `TransactionType.CREDIT` and returns the raw amount without precision loss.
    @Test
    @DisplayName("SQL Logic Check: Single Transaction should reflect in balance")
    void shouldCalculateSimpleBalance() {
        // GIVEN
        Account account = createRandomAccount();
        createEntry(account, new BigDecimal("100.00"), TransactionType.CREDIT);

        // WHEN
        BigDecimal balance = ledgerRepository.getBalance(account.getId());

        // THEN
        assertThat(balance).isEqualByComparingTo("100.00");
    }

    /// **Fuzz Test: High-Density Randomized Data Parity**.
    ///
    /// Executes 10 iterations of a stochastic financial simulation.
    /// In each run, 50 random [LedgerEntry] records are generated with randomized
    /// amounts and directions (Credit/Debit).
    ///
    /// **Verification Metric:**
    /// The "SQL Truth" (derived via database aggregation) must be identical to the
    /// "Java Truth" (derived via local `BigDecimal` summation). This test is
    /// critical for catching [RoundingMode] discrepancies or illegal SQL sign handling.
    ///
    ///
    @RepeatedTest(10)
    @DisplayName("SQL Logic Check: Aggregation matches Java Math for random datasets")
    void sqlShouldMatchJavaMath() {
        // 1. Setup Account (With Valid User)
        Account account = createRandomAccount();
        List<LedgerEntry> entries = new ArrayList<>();
        BigDecimal javaExpectedBalance = BigDecimal.ZERO;

        // 2. Generate 50 Random Entries
        for (int i = 0; i < 50; i++) {
            BigDecimal amount = BigDecimal.valueOf(random.nextDouble() * 1000).setScale(4, RoundingMode.HALF_EVEN);
            TransactionType type = random.nextBoolean() ? TransactionType.CREDIT : TransactionType.DEBIT;

            // Calculate Java Truth
            if (type == TransactionType.CREDIT) {
                javaExpectedBalance = javaExpectedBalance.add(amount);
            } else {
                javaExpectedBalance = javaExpectedBalance.subtract(amount);
            }

            // Prepare Entity
            entries.add(LedgerEntry.builder()
                    .account(account)
                    .amount(amount)
                    .transactionType(type)
                    .currency("USD")
                    .direction(type == TransactionType.CREDIT ? "IN" : "OUT")
                    .description("Fuzz Entry " + i)
                    .recordedAt(LocalDateTime.now())
                    .build());
        }

        // 3. Persist All (Batch Insert)
        ledgerRepository.saveAll(entries);
        ledgerRepository.flush();

        // 4. Query (The Code Under Test)
        BigDecimal sqlBalance = ledgerRepository.getBalance(account.getId());

        // 5. Assert
        assertThat(sqlBalance)
                .as("SQL Sum must equal Java Sum")
                .isEqualByComparingTo(javaExpectedBalance);
    }

    // --- HELPER METHODS ---

    /// **Test Utility: Provisioning of Valid Domain State**.
    ///
    /// Performs a multi-stage persistence operation to satisfy strict database constraints:
    /// 1. Persists a new [User] with a unique identity and mandatory roles.
    /// 2. Provisions an [Account] linked to the persisted owner with a unique IBAN.
    ///
    /// This ensures that [LedgerEntry] records do not violate `Foreign Key` constraints
    /// during insertion.
    ///
    /// @return A persisted [Account] entity ready for ledger injection.
    private Account createRandomAccount() {
        // 1. Create and Save the Owner first (Constraint Fix)
        String randomSuffix = UUID.randomUUID().toString().substring(0, 8);
        User owner = User.builder()
                .email("test.user." + randomSuffix + "@opayque.com")
                .password("hashed_secret") // Correct field name
                .fullName("Test User " + randomSuffix) // Mandatory field (@Column(nullable = false))
                .role(Role.CUSTOMER) // Enum, not String
                //.createdAt(LocalDateTime.now()) // Optional: Hibernate handles this via @CreationTimestamp
                .build();

        User savedOwner = userRepository.save(owner); // <--- SAVE USER FIRST

        // 2. Create an Account linked to that Owner
        Account acct = Account.builder()
                .user(savedOwner) // <--- LINK IT
                .currencyCode("USD")
                .iban("US" + randomSuffix) // Unique IBAN
                .build();

        return accountRepository.save(acct);
    }

    /// **Test Utility: Atomic Entry Injection**.
    ///
    /// Directly persists a [LedgerEntry] into the database. It simulates the "Recorded At"
    /// timestamp and maps the `direction` field to align with the project's partitioning
    /// and audit requirements.
    ///
    /// @param account The target wallet for the entry.
    /// @param amount The financial value to be recorded.
    /// @param type The classification (CREDIT/DEBIT) determining the aggregation sign.
    private void createEntry(Account account, BigDecimal amount, TransactionType type) {
        ledgerRepository.save(LedgerEntry.builder()
                .account(account)
                .amount(amount)
                .transactionType(type)
                .currency("USD")
                .direction(type == TransactionType.CREDIT ? "IN" : "OUT")
                .recordedAt(LocalDateTime.now())
                .build());
    }
}