package com.opayque.api.wallet.service;

import com.opayque.api.wallet.entity.LedgerEntry;
import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.integration.currency.CurrencyExchangeService;
import com.opayque.api.wallet.dto.CreateLedgerEntryRequest;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.TransactionType;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.repository.LedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/// **High-Concurrency Ledger Stress Test — Reliability & ACID Verification Suite**.
///
/// This suite executes "Thundering Herd" scenarios against the [LedgerService] to audit the
/// system's resilience under intense parallel load. It verifies that the "Reliability-First"
/// approach holds true when multiple threads compete for the same [Account] resources.
///
/// **Architectural Objectives:**
/// * **Concurrency Control:** Validates that Pessimistic Locking (`SELECT ... FOR UPDATE`)
///   correctly serializes transactions to prevent "Lost Updates" or "Phantom Money".
/// * **ACID Compliance:** Ensures all 50 parallel transactions are either fully committed
///   or rolled back, maintaining a zero-loss state.
/// * **Infrastructure Realism:** Uses **Testcontainers** to mirror production PostgreSQL
///   sequence and locking behaviors, avoiding H2 inconsistencies.
///
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none"
})
@Testcontainers
@Tag("stress")
class LedgerStressTest {

    /// **Production-Mirror Database Instance**.
    ///
    /// Orchestrates a native PostgreSQL 15 container. This ensures that the stress test
    /// interacts with real MVCC (Multi-Version Concurrency Control) and row-level locking
    /// mechanisms used in the production environment.
    @Container
    static final GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse("postgres:15-alpine"))
            .withEnv("POSTGRES_DB", "opayque_test")
            .withEnv("POSTGRES_USER", "ci_user")
            .withEnv("POSTGRES_PASSWORD", "ci_password")
            .withExposedPorts(5432);

    /// **Ephemeral Cache Instance**.
    ///
    /// Spins up a Redis 7 container to support the standard application context, ensuring
    /// that idempotency checks or blocklisting logic do not interfere with transaction throughput.
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    /// Configures dynamic properties to establish connectivity with the Testcontainers.
    ///
    /// **Technical Criticalities:**
    /// * **Hikari Pool Size:** Set to 60 to prevent connection starvation during 50-thread bursts.
    /// * **Dialect Force:** Explicitly overrides H2 settings to ensure PostgreSQL-specific
    ///   locking syntax is utilized.
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
        registry.add("spring.jpa.properties.hibernate.jdbc.use_get_generated_keys", () -> "true");
        registry.add("spring.jpa.properties.hibernate.temp.use_jdbc_metadata_defaults", () -> "false");
    }

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

    @MockitoBean
    private CurrencyExchangeService exchangeService;

    /// Purges all tables before each stress iteration to ensure sub-millisecond
    /// sequence alignment and state isolation.
    @BeforeEach
    void setup() {
        ledgerRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        // Deterministic rates to eliminate external API latency as a factor in the stress test
        when(exchangeService.getRate(anyString(), anyString())).thenReturn(BigDecimal.ONE);
    }

    /// **Scenario: Parallel Balance Aggregation**.
    ///
    /// Simulates 50 simultaneous $10.00 credits to a single USD account.
    ///
    /// **Audit Criteria:**
    /// 1. **Zero Data Loss:** All 50 transactions must be persisted in the [LedgerEntry] log.
    /// 2. **Balance Precision:** The final balance must equal exactly $500.00.
    /// 3. **Blocking Efficiency:** Pessimistic locks must hold without causing deadlocks or timeouts.
    ///
    /// @throws InterruptedException If the thread pool or latch is interrupted.
    @Test
    @DisplayName("Concurrency: Should process 50 parallel transactions without data loss (Thundering Herd)")
    void shouldProcessConcurrentTransactionsWithoutDataLoss() throws InterruptedException {

        // 1. Arrange: Persist parent user and account to satisfy Referential Integrity
        User stressUser = User.builder()
                .email("stress.test@opayque.com")
                .password("hashed-dummy-pw")
                .fullName("Stress Tester")
                .role(Role.CUSTOMER)
                .build();
        stressUser = userRepository.saveAndFlush(stressUser);

        Account account = Account.builder()
                .user(stressUser)
                .currencyCode("USD")
                .iban("US001234567890")
                .build();

        account = accountRepository.saveAndFlush(account);
        UUID accountId = account.getId();

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());

        // 2. Act: Trigger the simultaneous flood of ledger requests
        UUID finalAccountId = accountId;
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    ledgerService.recordEntry(new CreateLedgerEntryRequest(
                            finalAccountId,
                            BigDecimal.TEN,
                            "USD",
                            TransactionType.CREDIT,
                            "Stress Test",
                            null
                    ));
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        assertThat(completed).as("Stress test timed out! Possible deadlock in @Transactional logic.").isTrue();

        // 3. Stabilization: Buffer to ensure MVCC snapshots are flushed and consistent
        Thread.sleep(1000);

        // 4. Verification: Audit the final state
        assertThat(exceptions).isEmpty();

        assertThat(ledgerRepository.count())
                .as("Atomic persistence failed: Not all 50 entries were saved!")
                .isEqualTo(threadCount);

        BigDecimal balance = ledgerService.calculateBalance(accountId);
        assertThat(balance.stripTrailingZeros())
                .as("Dynamic Balance Aggregation mismatch!")
                .isEqualByComparingTo(new BigDecimal("500").stripTrailingZeros());
    }

    /// **Scenario: Mixed Currency Concurrency Stress**.
    ///
    /// Simulates a mixed load of USD and EUR transactions targeting a single EUR wallet.
    /// This validates the interaction between `Pessimistic Locking` and the [CurrencyExchangeService].
    ///
    /// **Mathematical Invariant:**
    /// * 25 EUR Transactions * 10 = 250 EUR
    /// * 25 USD Transactions * 10 * 0.90 (Rate) = 225 EUR
    /// * **Target Sum:** 475.00 EUR
    ///
    /// @throws InterruptedException If synchronization fails.
    @Test
    @DisplayName("Concurrency: Mixed Currency Load (Locking & Caching Check)")
    void shouldHandleMixedCurrencyConcurrency() throws InterruptedException {

        // Arrange
        User mixedUser = User.builder()
                .email("mixed.test@opayque.com")
                .password("hashed-dummy-pw")
                .fullName("Mixed Tester")
                .role(Role.CUSTOMER)
                .build();
        mixedUser = userRepository.saveAndFlush(mixedUser);

        Account eurAccount = Account.builder()
                .user(mixedUser)
                .currencyCode("EUR")
                .iban("DE001234567890")
                .build();

        eurAccount = accountRepository.saveAndFlush(eurAccount);
        UUID accountId = eurAccount.getId();

        when(exchangeService.getRate("USD", "EUR")).thenReturn(new BigDecimal("0.90"));

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());

        // Act
        for (int i = 0; i < threadCount; i++) {
            boolean isUsd = (i % 2 == 0);
            executor.submit(() -> {
                try {
                    ledgerService.recordEntry(new CreateLedgerEntryRequest(
                            accountId,
                            BigDecimal.TEN,
                            isUsd ? "USD" : "EUR",
                            TransactionType.CREDIT,
                            "Mixed Stress",
                            null
                    ));
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        assertThat(completed).as("Mixed stress test timed out!").isTrue();

        Thread.sleep(1000);

        // Assert
        assertThat(exceptions).isEmpty();

        assertThat(ledgerRepository.count())
                .as("Data Loss Detected in mixed currency stream!")
                .isEqualTo(50);

        BigDecimal balance = ledgerService.calculateBalance(accountId);
        assertThat(balance.stripTrailingZeros())
                .as("Multi-currency balance aggregation mismatch!")
                .isEqualByComparingTo(new BigDecimal("475").stripTrailingZeros());
    }
}