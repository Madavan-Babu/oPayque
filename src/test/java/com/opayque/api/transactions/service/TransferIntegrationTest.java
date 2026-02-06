package com.opayque.api.transactions.service;

import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.infrastructure.exception.InsufficientFundsException;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.LedgerEntry;
import com.opayque.api.wallet.entity.TransactionType;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.repository.LedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// **Epic 3: Atomic Transaction Engine — Full-Slice Integration Audit**.
///
/// This suite validates the orchestration of fund movements across the [TransferService],
/// [LedgerRepository], and [AccountRepository]. It ensures that the "Magic Ledger"
/// maintains strict `ACID` properties during real-world database interactions.
///
/// **Architectural Objectives:**
/// - **Atomicity Verification:** Confirms that debits and credits are treated as a single
///   indivisible unit of work.
/// - **Referential Integrity:** Validates that shared business identifiers (Reference IDs)
///   link related entries for reconciliation.
/// - **Infrastructure Parity:** Uses `Testcontainers` to mirror production `PostgreSQL`
///   and `Redis` configurations, bypassing H2 limitations.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TransferIntegrationTest {

    // --- INFRASTRUCTURE (Postgres + Redis) ---

    /// **Ephemeral Persistence Layer (PostgreSQL 15)**.
    ///
    /// Provisions a containerized database instance to test native row-level locking
    /// and `DECIMAL(19, 4)` precision. Essential for verifying the `PESSIMISTIC_WRITE`
    /// strategy defined in the [AccountRepository].
    @Container
    static final GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse("postgres:15-alpine"))
            .withEnv("POSTGRES_DB", "opayque_test")
            .withEnv("POSTGRES_USER", "ci_user")
            .withEnv("POSTGRES_PASSWORD", "ci_password")
            .withExposedPorts(5432);

    /// **Ephemeral Cache Layer (Redis 7)**.
    ///
    /// Supports the application context for cross-cutting security and idempotency
    /// requirements. Although not directly tested in this slice, it ensures the
    /// `Spring Boot` context initializes correctly.
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    /// **Infrastructure Orchestration & Pool Tuning**.
    ///
    /// Dynamically maps container credentials to the `Spring` environment.
    ///
    /// **Critical Overrides:**
    /// - **Dialect Force:** Reinstates `PostgreSQLDialect` to ensure correct `SQL` generation.
    /// - **HikariCP Tuning:** Configures `maximum-pool-size` to 60 to prevent connection
    ///   starvation during transactional bursts.
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/opayque_test");
        registry.add("spring.datasource.username", () -> "ci_user");
        registry.add("spring.datasource.password", () -> "ci_password");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "60");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "10");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "30000");
    }

    // --- DEPENDENCIES ---
    @Autowired private TransferService transferService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerRepository ledgerRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanSlate() {
        ledgerRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    /// **Requirement Audit: Transactional Atomicity & Linking**.
    ///
    /// Verifies that a successful transfer generates two [LedgerEntry] records (one `DEBIT`,
    /// one `CREDIT`) sharing a unique, non-null `referenceId`.
    ///
    /// **Verification Criteria:**
    /// - `ReferenceId` parity between sender and receiver logs.
    /// - Directional markers (`IN`/`OUT`) align with the [TransactionType].
    /// - Logical amounts match the requested transfer value.
    @Test
    @DisplayName("Should persist atomic transfer with linked Reference ID in Real DB")
    void shouldPersistTransferAtomicallyWithReferenceId() {
        // 1. Arrange
        User sender = createUser("sender@opayque.com", "Sender User");
        Account senderAccount = createAccount(sender, "USD");
        seedFunds(senderAccount, new BigDecimal("500.00"));

        User receiver = createUser("receiver@opayque.com", "Receiver User");
        Account receiverAccount = createAccount(receiver, "USD");

        // 2. Act
        BigDecimal transferAmount = new BigDecimal("100.00");
        // FIX: Passing senderAccount.getId() (Account ID), NOT sender.getId() (User ID)
        String key = "int-test-" + UUID.randomUUID();
        transferService.transferFunds(senderAccount.getId(), receiver.getEmail(), transferAmount.toString(), "USD", key);

        // 3. Assert
        List<LedgerEntry> senderEntries = ledgerRepository.findByAccount(senderAccount);
        List<LedgerEntry> receiverEntries = ledgerRepository.findByAccount(receiverAccount);

        LedgerEntry debitEntry = senderEntries.stream()
                .filter(e -> e.getTransactionType() == TransactionType.DEBIT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Debit entry not found"));

        LedgerEntry creditEntry = receiverEntries.stream()
                .filter(e -> e.getTransactionType() == TransactionType.CREDIT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Credit entry not found"));

        assertThat(debitEntry.getReferenceId()).isNotNull();
        assertThat(debitEntry.getReferenceId()).isEqualTo(creditEntry.getReferenceId());
        assertThat(debitEntry.getAmount()).isEqualByComparingTo(transferAmount);

        assertThat(debitEntry.getDirection()).isEqualTo("OUT");
        assertThat(creditEntry.getDirection()).isEqualTo("IN");
    }

    /// **Mathematical Audit: Dynamic Balance Aggregation**.
    ///
    /// Validates that the [LedgerRepository] aggregate query reflects the post-transfer
    /// state correctly. Ensures that `100.00 (Start) - 40.00 (Transfer) = 60.00 (Balance)`.
    @Test
    @DisplayName("Should update real balances correctly after transfer")
    void shouldUpdateBalancesCorrectly() {
        // 1. Arrange
        User sender = createUser("sender_math@opayque.com", "Math Sender");
        Account senderAccount = createAccount(sender, "USD");
        seedFunds(senderAccount, new BigDecimal("100.00")); // Start: $100

        User receiver = createUser("receiver_math@opayque.com", "Math Receiver");
        Account receiverAccount = createAccount(receiver, "USD"); // Start: $0

        // 2. Act
        // FIX: Passing Account ID
        String key = "int-test-" + UUID.randomUUID();
        transferService.transferFunds(senderAccount.getId(), receiver.getEmail(), "40.00", "USD", key);

        // 3. Assert
        BigDecimal senderBalance = ledgerRepository.getBalance(senderAccount.getId());
        BigDecimal receiverBalance = ledgerRepository.getBalance(receiverAccount.getId());

        assertThat(senderBalance).isEqualByComparingTo("60.00");
        assertThat(receiverBalance).isEqualByComparingTo("40.00");
    }

    /// **Resilience Audit: Automated Rollback on Business Failure**.
    ///
    /// Forces an [InsufficientFundsException] and confirms that the database session
    /// rolls back the entire transaction.
    ///
    /// **Success Condition:** The sender's ledger must contain only the initial `Seed Funds`
    /// record; no `DEBIT` entry should exist in the physical database.
    @Test
    @DisplayName("Should remain atomic (Rollback) on Insufficient Funds")
    void shouldRollbackOnInsufficientFunds() {
        // 1. Arrange
        User sender = createUser("broke@opayque.com", "Broke Sender");
        Account senderAccount = createAccount(sender, "USD");
        seedFunds(senderAccount, new BigDecimal("10.00")); // Only $10

        User receiver = createUser("rich@opayque.com", "Rich Receiver");
        createAccount(receiver, "USD");

        String key = "int-test-" + UUID.randomUUID();
        // 2. Act & Assert
        // FIX: Passing Account ID AND checking for specific Exception
        assertThatThrownBy(() ->
                transferService.transferFunds(senderAccount.getId(), receiver.getEmail(), "50.00", "USD", key)
        )
                .isInstanceOf(InsufficientFundsException.class); // This confirms it failed for the RIGHT reason

        // 3. Assert
        List<LedgerEntry> entries = ledgerRepository.findByAccount(senderAccount);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getDescription()).isEqualTo("Seed Funds");
    }

    // --- HELPERS ---

    private User createUser(String email, String fullName) {
        // FIX: saveAndFlush to ensure visibility in transactions
        return userRepository.saveAndFlush(User.builder()
                .email(email)
                .fullName(fullName)
                .password(passwordEncoder.encode("password123"))
                .role(Role.CUSTOMER)
                .build());
    }

    private Account createAccount(User user, String currency) {
        // FIX: saveAndFlush
        return accountRepository.saveAndFlush(Account.builder()
                .user(user)
                .currencyCode(currency)
                .iban("XX" + System.nanoTime())
                .build());
    }

    /// **Atomic Initial Credit Injection**.
    ///
    /// Directly persists a `CREDIT` entry into the database to establish baseline liquidity.
    /// This helper uses `saveAndFlush` to ensure that the data is visible to the
    /// [TransferService] transaction boundary.
    private void seedFunds(Account account, BigDecimal amount) {
        // FIX: saveAndFlush
        ledgerRepository.saveAndFlush(LedgerEntry.builder()
                .account(account)
                .amount(amount)
                .currency(account.getCurrencyCode())
                .transactionType(TransactionType.CREDIT)
                .direction("IN")
                .originalAmount(amount)
                .originalCurrency(account.getCurrencyCode())
                .exchangeRate(BigDecimal.ONE)
                .recordedAt(LocalDateTime.now())
                .description("Seed Funds")
                .referenceId(null)
                .build());
    }
}