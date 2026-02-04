package com.opayque.api.transactions.service;

import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.LedgerEntry;
import com.opayque.api.wallet.entity.TransactionType;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.repository.LedgerRepository;
import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;


/// **Epic 3: Atomic Transaction Engine — Double-Spend Vulnerability Audit**.
///
/// Labeled as "The Integrity Test," this suite validates the effectiveness of the engine's
/// `Pessimistic Locking` strategy in preventing a user from spending the same funds
/// twice via simultaneous network requests.
///
/// **Security Objectives:**
/// - **Race Condition Mitigation:** Confirms that the database-level write lock (`FOR UPDATE`)
///   atomically serializes concurrent balance checks.
/// - **Insufficient Funds Enforcement:** Verifies that exactly one transaction succeeds
///   while the second is rejected with a 4xx error, maintaining a non-negative balance.
/// - **Consistency Invariant:** Ensures the ledger remains a "Single Source of Truth"
///   where total debits never exceed the initial credit.
///
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Slf4j
class TransferDoubleSpendTest {

    // --- INFRASTRUCTURE (Standard) ---
    @Container
    static final GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse("postgres:15-alpine"))
            .withEnv("POSTGRES_DB", "opayque_test")
            .withEnv("POSTGRES_USER", "ci_user")
            .withEnv("POSTGRES_PASSWORD", "ci_password")
            .withExposedPorts(5432);

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    /// **Infrastructure Orchestration & Pool Tuning**.
    ///
    /// Injects dynamic container credentials and optimizes the `HikariCP` connection pool.
    /// Sets `maximum-pool-size` to 60 to prevent thread starvation during the
    /// simultaneous execution of parallel attack threads.
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
    }

    // --- DEPENDENCIES ---
    @Autowired private TransferService transferService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerRepository ledgerRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    /// **Transactional Cleanup & Data Isolation**.
    ///
    /// Performs a hard delete on all ledger and user tables. This ensures that
    /// randomized IBANs and stale balances from previous test runs do not
    /// contaminate the current vulnerability audit.
    @BeforeEach
    void cleanSlate() {
        ledgerRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }


    /// **Scenario: Concurrent Spend Contention (Race Condition Audit)**.
    ///
    /// Simulates a "Scammer" attempt to exploit network latency:
    /// 1. Scammer has a balance of exactly $100.00.
    /// 2. Two threads are launched simultaneously:
    ///    - Thread A: Send $100.00 to Merchant A.
    ///    - Thread B: Send $100.00 to Merchant B.
    ///
    /// **Validation Logic:**
    /// - **Serialization:** The [AccountRepository] must lock the scammer's row.
    /// - **The Result:** Exactly 1 Success and 1 Failure must be recorded.
    /// - **Balance Integrity:** The final balance must be $0.00, proving the system
    ///   blocked the second $100.00 outflow.
    ///
    /// @throws InterruptedException If the concurrent executor is interrupted.
    @Test
    @DisplayName("Vulnerability Check: Should prevent Double Spending of the same funds")
    void shouldPreventDoubleSpend() throws InterruptedException {
        // 1. Arrange: Scammer has exactly $100.00
        User scammer = createUser("scammer@opayque.com", "Scam Artist");
        Account scammerAccount = createAccount(scammer, "USD");
        seedFunds(scammerAccount, new BigDecimal("100.00"));

        User merchantA = createUser("merchantA@opayque.com", "Merchant A");
        createAccount(merchantA, "USD");

        User merchantB = createUser("merchantB@opayque.com", "Merchant B");
        createAccount(merchantB, "USD");

        // 2. The Attack: Send $100 to A AND $100 to B simultaneously.
        // Total attempt: $200. Available: $100.
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // Task 1: Send All Funds to Merchant A
        Runnable attack1 = () -> {
            try {
                latch.await();
                transferService.transferFunds(scammerAccount.getId(), merchantA.getEmail(), "100.00", "USD");
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            }
        };

        // Task 2: Send All Funds to Merchant B
        Runnable attack2 = () -> {
            try {
                latch.await();
                transferService.transferFunds(scammerAccount.getId(), merchantB.getEmail(), "100.00", "USD");
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            }
        };

        // 3. Act
        executor.submit(attack1);
        executor.submit(attack2);
        latch.countDown(); // Go!

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // 4. Assert: The "Solid AF" Check
        // If Logic is correct: 1 Success, 1 Fail (Insufficient Funds).
        // If Logic is flawed: 2 Success (Double Spend).
        log.info("Double Spend Results: Success={}, Fail={}", successCount.get(), failCount.get());

        // THE ASSERTION OF TRUTH
        assertThat(successCount.get()).as("Double Spend Detected! Both transfers succeeded!").isEqualTo(1);
        assertThat(failCount.get()).as("One transfer should have failed").isEqualTo(1);

        // Verify Balance is 0.00, not -100.00
        BigDecimal finalBalance = ledgerRepository.getBalance(scammerAccount.getId());
        assertThat(finalBalance).isEqualByComparingTo("0.00");
    }

    // --- HELPERS ---
    private User createUser(String email, String fullName) {
        return userRepository.saveAndFlush(User.builder()
                .email(email).fullName(fullName).password(passwordEncoder.encode("pwd")).role(Role.CUSTOMER).build());
    }

    /// **Internal Provisioning: Wallet Entity Construction**.
    ///
    /// Generates a valid [Account] entity linked to the provided user identity.
    /// Utilizes [System#nanoTime] to ensure high-entropy, unique IBANs for
    /// non-colliding transactional history.
    private Account createAccount(User user, String currency) {
        return accountRepository.saveAndFlush(Account.builder()
                .user(user).currencyCode(currency).iban("XX" + System.nanoTime()).build());
    }

    /// **Atomic Initial Credit Seeding**.
    ///
    /// Manually injects a `CREDIT` ledger entry to establish the "Seed Funds" for the
    /// scammer account. This bypasses the [TransferService] to ensure the test
    /// isolates the contention logic of the transfer rather than the seeding logic.
    private void seedFunds(Account account, BigDecimal amount) {
        ledgerRepository.saveAndFlush(LedgerEntry.builder()
                .account(account).amount(amount).currency(account.getCurrencyCode())
                .transactionType(TransactionType.CREDIT).direction("IN").originalAmount(amount)
                .originalCurrency(account.getCurrencyCode()).exchangeRate(BigDecimal.ONE)
                .recordedAt(LocalDateTime.now()).description("Seed").referenceId(null).build());
    }
}