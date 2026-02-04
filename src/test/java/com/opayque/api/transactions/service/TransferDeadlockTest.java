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
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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

/// Epic 3: Atomic Transaction Engine - Stress Testing.
///
/// "The Physics Test".
/// Simulates the classic "Bankers Deadlock" scenario where two users send money to each other
/// at the exact same millisecond. This verifies that the database locking strategy (Pessimistic Write)
/// correctly handles the collision without corrupting data.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Slf4j
class TransferDeadlockTest {

    // --- INFRASTRUCTURE (Identical to Integration Test) ---
    @Container
    static final GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse("postgres:15-alpine"))
            .withEnv("POSTGRES_DB", "opayque_test")
            .withEnv("POSTGRES_USER", "ci_user")
            .withEnv("POSTGRES_PASSWORD", "ci_password")
            .withExposedPorts(5432);

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

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

    @BeforeEach
    void cleanSlate() {
        ledgerRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Physics Check: Should preserve consistency during Deadlock (A->B and B->A)")
    void shouldHandleDeadlockGracefully() throws InterruptedException {
        // 1. Arrange: Two users with ample funds
        User alice = createUser("alice@opayque.com", "Alice User");
        Account aliceAccount = createAccount(alice, "USD");
        seedFunds(aliceAccount, new BigDecimal("1000.00"));

        User bob = createUser("bob@opayque.com", "Bob User");
        Account bobAccount = createAccount(bob, "USD");
        seedFunds(bobAccount, new BigDecimal("1000.00"));

        // 2. The Setup: Two threads, opposing directions
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1); // The "Starting Gun"
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger deadlockCount = new AtomicInteger(0);

        // Task 1: Alice sends 100 to Bob
        Runnable task1 = () -> {
            try {
                latch.await(); // Wait for gun
                transferService.transferFunds(aliceAccount.getId(), bob.getEmail(), "100.00", "USD");
                successCount.incrementAndGet();
            } catch (Exception e) {
                handleException(e, deadlockCount);
            }
        };

        // Task 2: Bob sends 100 to Alice
        Runnable task2 = () -> {
            try {
                latch.await(); // Wait for gun
                transferService.transferFunds(bobAccount.getId(), alice.getEmail(), "100.00", "USD");
                successCount.incrementAndGet();
            } catch (Exception e) {
                handleException(e, deadlockCount);
            }
        };

        // 3. Act: Fire!
        executor.submit(task1);
        executor.submit(task2);
        latch.countDown(); // Release the threads simultaneously

        // Wait for dust to settle
        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        // 4. Assert: Analyze the Battlefield
        // In a true pessimistic locking scenario without lock ordering, one transaction SHOULD be killed by Postgres.
        log.info("Stress Test Results: Success={}, Deadlocks={}", successCount.get(), deadlockCount.get());

        // We verify that Money was Conserved.
        // If 1 succeeded: Alice (-100), Bob (+100) -> Alice=900, Bob=1100.
        // If 2 succeeded (rare, but possible with timing): Alice (-100+100), Bob (+100-100) -> Alice=1000, Bob=1000.
        // If 0 succeeded: Alice=1000, Bob=1000.

        BigDecimal aliceBalance = ledgerRepository.getBalance(aliceAccount.getId());
        BigDecimal bobBalance = ledgerRepository.getBalance(bobAccount.getId());
        BigDecimal totalSystemMoney = aliceBalance.add(bobBalance);

        // INVARIANT: Total money in system must remain 2000.00
        // Atomicity means we never lose or gain money, even if a transaction dies.
        assertThat(totalSystemMoney).isEqualByComparingTo("2000.00");
    }

    private void handleException(Exception e, AtomicInteger deadlockCount) {
        // Postgres throws "CannotAcquireLockException" or "DeadlockLoserDataAccessException"
        if (e instanceof CannotAcquireLockException ||
                e instanceof ObjectOptimisticLockingFailureException ||
                e.getMessage().contains("deadlock")) {
            log.warn("Deadlock Detected & Caught: {}", e.getMessage());
            deadlockCount.incrementAndGet();
        } else {
            log.error("Unexpected Error", e);
        }
    }

    // --- HELPERS (Robust Versions from Integration Test) ---

    private User createUser(String email, String fullName) {
        return userRepository.saveAndFlush(User.builder()
                .email(email)
                .fullName(fullName)
                .password(passwordEncoder.encode("password123"))
                .role(Role.CUSTOMER)
                .build());
    }

    private Account createAccount(User user, String currency) {
        return accountRepository.saveAndFlush(Account.builder()
                .user(user)
                .currencyCode(currency)
                .iban("XX" + System.nanoTime())
                .build());
    }

    private void seedFunds(Account account, BigDecimal amount) {
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