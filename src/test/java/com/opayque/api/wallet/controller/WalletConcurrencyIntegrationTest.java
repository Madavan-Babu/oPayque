package com.opayque.api.wallet.controller;

import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// Multi-Currency Account Management - High-Concurrency Integrity Audit.
///
/// This suite executes a "Hellproof" stress test against the [AccountService] to verify
/// the atomicity and collision-resistance of the IBAN generation engine.
/// It specifically targets the database-backed [AccountRepository#getNextAccountNumber]
/// sequence to ensure that simultaneous wallet provisioning never results in duplicate
/// financial identifiers.
///
/// Infrastructure: Utilizes Testcontainers to orchestrate a native PostgreSQL 15
/// environment, mirroring production sequence isolation behavior.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class WalletConcurrencyIntegrationTest {

    /// **The Ephemeral Ledger Instance**
    ///
    /// Spins up a production-grade PostgreSQL container.
    /// We avoid H2 here because H2 sequences behave differently under high-concurrency
    /// than native PostgreSQL MVCC and sequence engines.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    // FIX: Explicitly override the H2 settings from application-test.yaml
    // The @ServiceConnection handles the URL, but NOT the driver/dialect.
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // 1. Force Postgres Driver (Overrides H2 from YAML)
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // 2. Force Postgres Dialect (Overrides H2 from YAML)
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired private AccountService accountService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    /// Purges the ledger before each stress iteration to ensure sub-millisecond
    /// sequence alignment and test isolation.
    @BeforeEach
    void setup() {
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    /// Stress Test: Massive Concurrent Provisioning.
    ///
    /// Simulates a "Thundering Herd" scenario where 50 distinct user identities attempt
    /// to open wallets simultaneously.
    ///
    /// Verification Criteria:
    /// 1. Zero Runtime Exceptions: The service layer must handle the transaction overhead.
    /// 2. Absolute Uniqueness: Every generated IBAN must be cryptographically and
    ///    numerically distinct.
    /// 3. ACID Compliance: The atomic sequence fetcher must provide non-overlapping
    ///    values despite the parallel execution.
    ///
    /// @throws InterruptedException If the thread pool is interrupted during execution.
    /// @throws ExecutionException If an internal wallet provisioning task fails.
    @Test
    @DisplayName("Stress Test: 50 Concurrent Users creating EUR wallets must get unique IBANs")
    void shouldHandleConcurrentWalletCreation() throws InterruptedException, ExecutionException {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        // Synchronizes threads to fire at the exact same millisecond.
        CountDownLatch latch = new CountDownLatch(threadCount);

        // 1. Arrange: Pre-provision distinct user identities in the database.
        List<User> users = IntStream.range(0, threadCount)
                .mapToObj(i -> User.builder()
                        .email("user" + i + "@concurrency.com")
                        .password(passwordEncoder.encode("pass"))
                        .fullName("User " + i)
                        .role(Role.CUSTOMER)
                        .build())
                .map(userRepository::save)
                .toList();

        // 2. The Task: Define the atomic creation unit for parallel dispatch.
        List<Callable<Account>> tasks = users.stream()
                .map(user -> (Callable<Account>) () -> {
                    latch.countDown(); // Decrement the latch.
                    latch.await();     // Wait for all 50 threads to reach this gate.
                    // Fire the request directly into the service layer.
                    return accountService.createAccount(user.getId(), "EUR");
                })
                .collect(Collectors.toList());

        // 3. Act: Trigger simultaneous execution across the thread pool.
        List<Future<Account>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        // 4. Collect Results: Aggregate all generated IBANs from the future objects.
        List<String> ibans = new ArrayList<>();
        for (Future<Account> future : futures) {
            ibans.add(future.get().getIban());
        }

        // 5. Verification: Audit the generated data for collision or truncation.

        // A. Cardinality Check: Ensure all 50 provisioning requests were fulfilled.
        assertThat(ibans).hasSize(threadCount);

        // B. Collision Audit: Utilize a Set to detect duplicate entries.
        // If Set size < List size, a duplicate IBAN was generated (Critical Fail).
        Set<String> uniqueIbans = new HashSet<>(ibans);
        assertThat(uniqueIbans).hasSize(threadCount)
                .as("Critical Security Failure: Database Sequence collision detected!");

        // C. Audit Trail: Output a sample of generated IBANs for manual verification.
        System.out.println("Sample IBANs generated: " + ibans.subList(0, 5));
    }
}