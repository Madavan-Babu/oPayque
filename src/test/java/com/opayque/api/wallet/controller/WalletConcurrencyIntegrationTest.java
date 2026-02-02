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

/// **Multi-Currency Account Management — High-Concurrency Integrity Audit**.
///
/// This suite executes a "Hellproof" stress test against the [AccountService] to verify
/// the atomicity and collision-resistance of the IBAN generation engine.
/// It specifically targets the database-backed `AccountRepository#getNextAccountNumber`
/// sequence to ensure that simultaneous wallet provisioning never results in duplicate
/// financial identifiers.
///
/// **Infrastructure Strategy:**
/// * Utilizes **Testcontainers** to orchestrate a native **PostgreSQL 15** environment.
/// * Mirrors production sequence isolation behavior, bypassing H2 limitations regarding concurrent MVCC operations.
/// * Enforces strict **ACID compliance** during parallel wallet creation.
///
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class WalletConcurrencyIntegrationTest {

    /// **The Ephemeral Ledger Instance**.
    ///
    /// Spins up a production-grade PostgreSQL container.
    /// Native PostgreSQL sequences are utilized to ensure non-overlapping values, a behavior
    /// often inconsistent in standard H2 memory databases during high-concurrency.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    /// **PostgreSQL Dialect Enforcement**.
    ///
    /// Explicitly overrides H2 settings from the test profile to ensure Hibernate utilizes
    /// the correct PostgreSQL dialect and drivers for native sequence fetching.
    ///
    /// @param registry The registry for dynamic property injection.
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Force Postgres Driver to override default H2 testing behavior
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // Force Postgres Dialect to support native sequence operations and partitioning
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired private AccountService accountService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    /// Purges the ledger and identity tables before each stress iteration.
    /// Ensures clean sequence alignment and prevents cross-contamination between test runs.
    @BeforeEach
    void setup() {
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    /// **Stress Test: Massive Concurrent Provisioning**.
    ///
    /// Simulates a "Thundering Herd" scenario where 50 distinct user identities attempt
    /// to open wallets at the exact same millisecond.
    ///
    /// **Verification Criteria:**
    /// 1. **Zero Runtime Exceptions:** The service layer must sustain high-concurrency transaction overhead.
    /// 2. **Absolute Uniqueness:** Every generated IBAN must be numerically distinct.
    /// 3. **ACID Compliance:** The atomic sequence fetcher must provide non-overlapping values.
    ///
    /// @throws InterruptedException If the thread pool is interrupted.
    /// @throws ExecutionException If an internal provisioning task fails.
    @Test
    @DisplayName("Stress Test: 50 Concurrent Users creating EUR wallets must get unique IBANs")
    void shouldHandleConcurrentWalletCreation() throws InterruptedException, ExecutionException {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // CountDownLatch forces threads to synchronize and fire simultaneously
        CountDownLatch latch = new CountDownLatch(threadCount);

        // 1. Arrange: Pre-provision distinct user identities to satisfy identity constraints.
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
                    latch.countDown(); // Ready...
                    latch.await();     // ...Set... Go!

                    // Dispatch the creation request into the ACID-controlled service layer
                    return accountService.createAccount(user.getId(), "EUR");
                })
                .collect(Collectors.toList());

        // 3. Act: Trigger the simultaneous execution flood across the thread pool.
        List<Future<Account>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        // 4. Collect Results: Aggregate all generated IBANs for audit.
        List<String> ibans = new ArrayList<>();
        for (Future<Account> future : futures) {
            ibans.add(future.get().getIban());
        }

        // 5. Verification: Comprehensive data audit for collisions or logic errors.

        // A. Cardinality Check: Ensure all 50 parallel requests were successfully fulfilled
        assertThat(ibans).hasSize(threadCount);

        // B. Collision Audit: Utilize a Set to detect any duplicate generated IBANs.
        // If Set size < List size, the sequence engine failed the atomicity requirement.
        Set<String> uniqueIbans = new HashSet<>(ibans);
        assertThat(uniqueIbans).hasSize(threadCount)
                .as("CRITICAL SECURITY FAILURE: Database Sequence collision detected in IBAN generation!");

        // C. Audit Trail: Output a sample subset for manual confirmation in the CI logs
        System.out.println("Sample IBANs generated: " + ibans.subList(0, 5));
    }
}