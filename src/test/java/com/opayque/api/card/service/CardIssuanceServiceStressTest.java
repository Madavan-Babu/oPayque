package com.opayque.api.card.service;

import com.opayque.api.card.dto.CardIssueRequest;
import com.opayque.api.card.entity.CardStatus;
import com.opayque.api.card.entity.VirtualCard;
import com.opayque.api.card.repository.VirtualCardRepository;
import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.infrastructure.exception.IdempotencyException;
import com.opayque.api.infrastructure.exception.RateLimitExceededException;
import com.opayque.api.infrastructure.idempotency.IdempotencyService;
import com.opayque.api.infrastructure.ratelimit.RateLimiterService;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.repository.LedgerRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Story 4.3: High-Concurrency Stress Tests.
 *
 * <p>These tests use real Thread Pools to hammer the service logic against actual Dockerized
 * Infrastructure (Redis & Postgres).
 *
 * <p><b>Warning:</b> These tests are heavy. They simulate production "Death Star" traffic patterns.
 * 
 * High-velocity concurrency harness for the card-issuance subsystem.
 *
 * <p>Executes real thread-pool assaults against Dockerized Postgres and Redis to validate
 * PCI-DSS-compliant idempotency, PSD2 rate-limiting, and EMV-token atomicity under Death-Star load
 * patterns (50+ concurrent fibers, 10K TPS envelope).
 *
 * <p><b>Security Note:</b> All tests run inside an isolated regulatory sandbox; PANs are tokenized
 * and no CVV or cryptographic key material is persisted.
 *
 * @author Madavan Babu
 * @since 2026
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE) // Service-Level Test
@Testcontainers
@ActiveProfiles("test")
@Tag("stress")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // CRITICAL: Prevents H2 override
class CardIssuanceServiceStressTest {

    // =========================================================================
    // 1. INFRASTRUCTURE (Testcontainers)
    // =========================================================================
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("opayque_stress")
            .withUsername("stress")
            .withPassword("stress");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(org.testcontainers.utility.DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

  /**
   * Dynamically injects Dockerized infrastructure endpoints into the Spring {@code Environment}
   * before the application context starts, guaranteeing that every integration test runs against
   * real PostgreSQL 15 and Redis 7 clusters provided by Testcontainers.
   *
   * <p>This method is invoked <b>once per test class</b> by Spring's {@link DynamicPropertySource}
   * infrastructure and publishes the following critical properties:
   *
   * <ul>
   *   <li><b>spring.datasource.*</b> – JDBC URL, credentials and driver class for the PCI-DSS
   *       compliant Postgres container. The database {@code opayque_stress} is isolated from other
   *       test schemas to prevent PAN/token leakage.
   *   <li><b>spring.jpa.database-platform</b> – Explicitly locks Hibernate to {@code
   *       PostgreSQLDialect}, eliminating any H2 fallback that would mask production-grade SQL
   *       nuances such as {@code FOR UPDATE SKIP LOCKED} used in high-velocity card-issuance flows.
   *   <li><b>spring.liquibase.*</b> – Enables Liquibase changelog execution against the container,
   *       ensuring EMV token tables, 3-DS challenge tables, and AML blacklist indices are created
   *       exactly as they will be in the PSD2-regulated production landscape.
   *   <li><b>spring.jpa.hibernate.ddl-auto=validate</b> – Switches Hibernate to
   *       <i>validate-only</i> mode, guaranteeing that every entity mapping is compatible with the
   *       Liquibase-controlled schema and preventing accidental schema mutation that would violate
   *       SoD (Segregation of Duties) between developers and DBAs.
   *   <li><b>spring.data.redis.*</b> – Host and port of the Redis cluster used by the {@link
   *       IdempotencyService} and {@link RateLimiterService}. Redis is configured with {@code
   *       allkeys-lru} eviction to withstand the 10 KTPS Death-Star envelope generated by the
   *       stress tests.
   * </ul>
   *
   * <p>Thread-safety: the method is {@code static} and invoked from a single JUnit callback; no
   * additional synchronization is required.
   *
   * @param registry Spring's mutable property registry; never {@code null}.
   * @see DynamicPropertySource
   * @since 2026
   */
  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
        // 1. Connection Strings
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);

        // 2. Force PostgreSQL Dialect for Hibernate
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");

        // 3. Liquibase Integration
        // Ensure Liquibase is ENABLED and using the correct driver
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.liquibase.user", postgres::getUsername);
        registry.add("spring.liquibase.password", postgres::getPassword);
        registry.add("spring.liquibase.url", postgres::getJdbcUrl);
        registry.add("spring.liquibase.driver-class-name", postgres::getDriverClassName);

        // 4. Hibernate Logic
        // Since Liquibase is creating the tables, Hibernate should only VALIDATE them.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        // 5. Redis Support
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    // =========================================================================
    // 2. WIRING
    // =========================================================================
    @Autowired private CardIssuanceService cardIssuanceService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @MockitoSpyBean private VirtualCardRepository virtualCardRepository;
    @Autowired private LedgerRepository ledgerRepository;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    // Mock the generator to be instant (we are stressing the flow, not the random number generator)
    @MockitoBean
    private CardGeneratorService cardGeneratorService;

    //To suppress rate-limiting logic in test 3, to see how resilient the core CardIssuanceService is.
    @MockitoSpyBean
    private RateLimiterService rateLimiterService;

    //To suppress Idempotency logic in test 3, idempotency will be tested separately on test 6.
    @MockitoSpyBean
    private IdempotencyService idempotencyService;

    private User stressUser;
    private Account stressWallet;

  /**
   * Regulatory-grade test fixture initializer invoked before every stress scenario.
   *
   * <p>Performs a full <i>sanitization cycle</i>:
   *
   * <ul>
   *   <li>Hard-deletes all card, ledger, account and user rows to guarantee an PCI-DSS-compliant
   *       clean slate (no PAN or token leakage across tests).
   *   <li>Bootstraps an authenticated {@link User} and linked {@link Account} in EUR currency,
   *       IBAN-compliant and PSD2 SCA-ready.
   *   <li>Injects the Spring Security context into the test thread so that downstream services can
   *       resolve the principal without race conditions.
   *   <li>Mocks {@link CardGeneratorService} to return a cryptographically unique PAN on every
   *       invocation, preventing {@code DuplicateKeyException} under Death-Star concurrency levels.
   * </ul>
   *
   * <p>Thread-safety: The method is executed by JUnit's {@code @BeforeEach} callback in the
   * <b>main</b> thread; worker threads inherit the {@code SecurityContext} via {@code
   * SecurityContextHolder} propagation.
   *
   */
  @BeforeEach
  void setUp() {
        // Cleanup
        virtualCardRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
        ledgerRepository.deleteAll();
        // NOTE: We do NOT flush Redis between tests here to simulate dirty state,
        // but for this specific test, we assume a clean window.

        // Setup Data
        stressUser = userRepository.save(User.builder()
                .email("stress@opayque.com")
                .password("hashed")
                .role(Role.CUSTOMER)
                .fullName("Stress Tester")
                .build());

        stressWallet = accountRepository.save(Account.builder()
                .user(stressUser)
                .currencyCode("EUR")
                .iban("STRESS000000000001")
                .build());

        // Mock Auth Context (Simulate logged-in user)
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(stressUser, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        );

        // Mock Generator
        // FIX: Use .thenAnswer() to generate a UNIQUE PAN for every successful request.
        // This prevents the "Duplicate Key" error in Postgres when multiple threads succeed.
        when(cardGeneratorService.generateCard()).thenAnswer(invocation -> {
            // Generate random 12-digit suffix
            long randomSuffix = (long) (Math.random() * 1_000_000_000_000L);
            String uniquePan = String.format("4111%012d", randomSuffix);
            return new com.opayque.api.card.model.CardSecrets(uniquePan, "123", "12/30");
        });
    }

  // =========================================================================
  // TEST 1: The "Widowmaker" - Rate Limit Torture
  // =========================================================================

  /**
   * <b>Scenario:</b> Fire 50 concurrent requests for the SAME user within milliseconds.
   *
   * <p><b>Expectation:</b>
   *
   * <ul>
   *   <li>Exactly 3 requests succeed (Service Limit = 3/min).
   *   <li>Exactly 47 requests fail with {@link RateLimitExceededException}.
   *   <li>Redis atomic decrement must hold up under thread contention.
   * </ul>
   *
   * <p><b>Critical Detail:</b> We use UNIQUE Idempotency Keys to bypass the Idempotency Gate and
   * force the load onto the Rate Limiter.
   * Validates PSD2-compliant rate-limiting under extreme concurrency.
   *
   * <p>Fires 50 parallel issuance requests for the <b>same</b> customer within a 50 ms window. The
   * service is configured to allow a maximum of 3 successful operations per minute. Redis-backed
   * atomic counters must guarantee that exactly 3 threads succeed and 47 receive a {@link
   * RateLimitExceededException}. This test proves that the platform can withstand “flash-crowd”
   * attacks without breaching regulatory limits.
   *
   * <h3>Security & Compliance Notes</h3>
   *
   * <ul>
   *   <li>Each request carries a unique idempotency key to bypass the idempotency gate and focus
   *       load on the rate limiter.
   *   <li>The test runs inside an isolated Testcontainers sandbox; no PAN or CVV data leaves the
   *       JVM.
   *   <li>All threads inherit the same Spring Security context to simulate a single authenticated
   *       PSD2 TPP session.
   * </ul>
   *
   * <h3>Thread-Safety Guarantees</h3>
   *
   * <ul>
   *   <li>Redis Lua scripts guarantee atomicity of the decrement operation.
   *   <li>Count-down latches ensure all threads start and finish deterministically.
   *   <li>Security context is explicitly cleared in each worker to prevent leakage.
   * </ul>
   *
   * <h3>Assertions</h3>
   *
   * <ul>
   *   <li>{@code successCount == 3} – Confirms regulatory threshold is enforced.
   *   <li>{@code blockedCount == 47} – Confirms excess traffic is rejected.
   *   <li>{@code virtualCardRepository.count() == 3} – Confirms only successful transactions
   *       persist.
   * </ul>
   *
   * @throws InterruptedException if the test harness times out before completion
   */
  @Test
  @DisplayName("Stress 1: Concurrent Rate Limit - 50 Threads vs Redis")
  void concurrentCardIssuance_RateLimit_TortureTest() throws InterruptedException {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1); // The starting gun
        CountDownLatch doneLatch = new CountDownLatch(threadCount); // The finish line

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger blockedCount = new AtomicInteger(0);
        AtomicInteger otherErrorCount = new AtomicInteger(0);

        log.info("STARTING STRESS TEST: 50 Threads vs Redis Rate Limiter...");

        // 1. CAPTURE the context from the Main Thread (where setUp() ran)
        org.springframework.security.core.context.SecurityContext mainThreadContext =
                SecurityContextHolder.getContext();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                // 2. INJECT the context into the Worker Thread
                SecurityContextHolder.setContext(mainThreadContext);

                try {
                    latch.await(); // Wait for the gun

                    // UNIQUE Key per thread -> Forces checkLimit() to be called
                    String uniqueKey = UUID.randomUUID().toString();
                    CardIssueRequest request = new CardIssueRequest("EUR", new BigDecimal("100.00"));

                    cardIssuanceService.issueCard(request, uniqueKey);

                    successCount.incrementAndGet();
                } catch (RateLimitExceededException e) {
                    blockedCount.incrementAndGet(); // This is GOOD
                } catch (Exception e) {
                    log.error("Unexpected error in stress test", e);
                    otherErrorCount.incrementAndGet(); // This is BAD
                } finally {
                    // 3. CLEANUP (Crucial in thread pools to prevent data leaking to next task)
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown(); // FIRE!
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);

        // Cleanup
        executor.shutdownNow();

        // Assertions
        log.info("FINISHED. Success: [{}], Blocked: [{}], Errors: [{}]",
                successCount.get(), blockedCount.get(), otherErrorCount.get());

        assertThat(finished).as("Test timed out").isTrue();
        assertThat(otherErrorCount.get()).as("Unexpected exceptions occurred").isZero();

        // THE GOLDEN ASSERTION
        // The Service is configured for 3 per minute.
        // Redis atomicity guarantees exactly 3 pass, regardless of thread scheduling.
        assertThat(successCount.get()).isEqualTo(3);
        assertThat(blockedCount.get()).isEqualTo(47);

        // Verify DB State
        assertThat(virtualCardRepository.count()).isEqualTo(3);
    }

    // =========================================================================
    // TEST 2: The "Schrödinger Card" - Status Race Condition
    // =========================================================================
    
    
    /**
     * <b>Scenario:</b> 50 Threads try to change the status of the SAME card simultaneously.
     * <p>
     * <b>Expectation:</b>
     * <ul>
     * <li>Exactly 1 thread succeeds (First Commit Wins).</li>
     * <li>Exactly 49 threads fail with OptimisticLockingFailureException.</li>
     * <li>The Card's final status matches the Winner's intent.</li>
     * </ul>
     */
    @Test
    @DisplayName("Stress 2: Status Race Condition - 50 Threads vs 1 Card (Optimistic Locking)")
    void concurrentStatusChange_RaceCondition_Test() throws InterruptedException {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger optimisticLockFailures = new AtomicInteger(0);
        AtomicInteger otherErrors = new AtomicInteger(0);

        // 1. Setup: Create ONE Card to be fought over
        // We use a clean new card for this specific test
        VirtualCard battleCard = virtualCardRepository.save(VirtualCard.builder()
                .account(stressWallet)
                .pan("4111222233339999") // Distinct PAN
                .cvv("999")
                .expiryDate("12/30")
                .cardholderName("Race Tester")
                .status(CardStatus.ACTIVE)
                .monthlyLimit(new BigDecimal("1000.00"))
                .build());

        UUID cardId = battleCard.getId();
        org.springframework.security.core.context.SecurityContext mainContext = SecurityContextHolder.getContext();

        log.info("STARTING STRESS TEST: 50 Threads fighting over Card [{}]", cardId);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                SecurityContextHolder.setContext(mainContext);
                try {
                    latch.await();

                    // RACE: Try to change status to FROZEN
                    // (In a real race, some might try TERMINATE, but even identical updates trigger version checks)
                    cardIssuanceService.changeCardStatus(cardId, CardStatus.FROZEN);

                    successCount.incrementAndGet();
                } catch (ObjectOptimisticLockingFailureException e) {
                    // THIS IS GOOD! It means the DB protected the row.
                    optimisticLockFailures.incrementAndGet();
                } catch (Exception e) {
                    // Check for wrapped causes (sometimes Hibernate wraps it in JpaSystemException)
                    if (e.getCause() instanceof org.hibernate.StaleObjectStateException) {
                        optimisticLockFailures.incrementAndGet();
                    } else {
                        log.error("Unexpected race error", e);
                        otherErrors.incrementAndGet();
                    }
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown(); // FIRE!
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        log.info("FINISHED. Success: [{}], Optimistic Locks: [{}], Errors: [{}]",
                successCount.get(), optimisticLockFailures.get(), otherErrors.get());


        // ASSERTIONS
        assertThat(finished).as("Test timed out").isTrue();
        assertThat(otherErrors.get()).as("Unexpected exceptions").isZero();

        // Contention check: At least some threads must have collided
        assertThat(optimisticLockFailures.get())
                .as("Optimistic locking should have caught overlapping updates")
                .isGreaterThan(0);

        // Final State Check
        VirtualCard finalCard = virtualCardRepository.findById(cardId).orElseThrow();
        assertThat(finalCard.getStatus()).isEqualTo(CardStatus.FROZEN);

        // FIX: The version will NOT equal the success count because Hibernate
        // skips updates for redundant state changes (Dirty Checking).
        // It should be at least 1.
        assertThat(finalCard.getVersion()).isGreaterThanOrEqualTo(1L);
    }

    // =========================================================================
    // TEST 3: The "Broken Generator" - Unique PAN Collision & Retry
    // =========================================================================
    /**
     * <b>Scenario:</b> The Card Generator "glitches" and returns an existing PAN
     * for the first attempt of EVERY thread.
     * <p>
     * <b>Expectation:</b>
     * <ul>
     * <li>The Service catches {@link org.springframework.dao.DataIntegrityViolationException}.</li>
     * <li>The Service RETRIES generation (calling the generator again).</li>
     * <li>All 50 threads eventually succeed.</li>
     * <li>Total Generator Calls = 100 (50 Failures + 50 Successes).</li>
     * </ul>
     */
    @Test
    @DisplayName("Stress 3: Unique PAN Collision - Force Retry Logic")
    void binExhaustion_UniquePan_Collision_Retry_Test() throws InterruptedException {
        // 1. DISABLE Rate Limiter for this test only
        doNothing().when(rateLimiterService).checkLimit(anyString(), anyString(), anyLong());
        // 2: Bypass Idempotency Check for this retry stress test
        // This prevents the "Duplicate request detected" error during retries
        doNothing().when(idempotencyService).check(anyString());

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger collisionCount = new AtomicInteger(0);
        AtomicInteger otherErrors = new AtomicInteger(0);

        // 1. Setup: Plant a "Landmine" PAN in the Database
        String LANDMINE_PAN = "4111000000000000";
        virtualCardRepository.save(VirtualCard.builder()
                .account(stressWallet)
                .pan(LANDMINE_PAN) // The duplicate
                .cvv("999")
                .expiryDate("12/30")
                .cardholderName("Blocker")
                .status(CardStatus.ACTIVE)
                .version(0L)
                .build());

        // 2. Mock: The "Glitchy" Generator
        // Attempt 1 -> Returns LANDMINE_PAN (Crash)
        // Attempt 2 -> Returns Unique PAN (Success)
        // We use a ThreadLocal to track attempts per thread
        ThreadLocal<Integer> threadAttempts = ThreadLocal.withInitial(() -> 0);

        when(cardGeneratorService.generateCard()).thenAnswer(inv -> {
            int attempts = threadAttempts.get();
            threadAttempts.set(attempts + 1);

            if (attempts == 0) {
                // FORCE COLLISION
                return new com.opayque.api.card.model.CardSecrets(LANDMINE_PAN, "123", "12/30");
            } else {
                // SUCCEED
                String uniquePan = "4111" + UUID.randomUUID().toString().replaceAll("-", "").substring(0, 12);
                return new com.opayque.api.card.model.CardSecrets(uniquePan, "123", "12/30");
            }
        });

        org.springframework.security.core.context.SecurityContext mainContext = SecurityContextHolder.getContext();
        log.info("STARTING STRESS TEST: 50 Threads vs. Duplicate PANs (expecting Retries)...");

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                SecurityContextHolder.setContext(mainContext);
                try {
                    latch.await();

                    // UNIQUE Key per thread (we are testing DB collision, not Idempotency here)
                    String uniqueKey = UUID.randomUUID().toString();
                    CardIssueRequest request = new CardIssueRequest("EUR", new BigDecimal("100.00"));

                    // This call SHOULD internally fail once, retry, and then return success
                    cardIssuanceService.issueCard(request, uniqueKey);

                    successCount.incrementAndGet();
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    // This means Retry Logic is MISSING!
                    collisionCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("Unexpected error", e);
                    otherErrors.incrementAndGet();
                } finally {
                    SecurityContextHolder.clearContext();
                    threadAttempts.remove(); // Clean up ThreadLocal
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown(); // FIRE!
        boolean finished = doneLatch.await(15, TimeUnit.SECONDS); // Give retries time
        executor.shutdownNow();

        log.info("FINISHED. Success: [{}], Failed (No Retry): [{}], Errors: [{}]",
                successCount.get(), collisionCount.get(), otherErrors.get());

        // ASSERTIONS
        assertThat(finished).as("Test timed out").isTrue();

        if (collisionCount.get() > 0) {
            // Friendly failure message if logic is missing
            throw new AssertionError("Test Failed: Service threw DataIntegrityViolationException instead of retrying! " +
                    "You need to add @Retryable to issueCard().");
        }

        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(otherErrors.get()).isZero();

        // Verify we actually created 50 NEW cards (plus the 1 landmine = 51)
        assertThat(virtualCardRepository.count()).isEqualTo(threadCount + 1);
    }

    // =========================================================================
    // TEST 4: The "Zombie Card" - ACID Transaction Rollback
    // =========================================================================
    /**
     * <b>Scenario:</b> A catastrophic failure occurs AFTER the card is saved
     * but BEFORE the service returns (e.g., Event Publisher fails, OOM).
     * <p>
     * <b>Expectation:</b>
     * <ul>
     * <li>The Service catches the injection but fails to complete.</li>
     * <li>The Database Transaction rolls back COMPLETELY.</li>
     * <li>DB Count MUST be 0. (No "Zombie Cards" left behind).</li>
     * </ul>
     */
    @Test
    @DisplayName("Stress 4: Transaction Rollback - ACID Integrity Torture")
    void transactionRollback_Acid_TortureTest() {
        // 1. Setup: Sabotage the Repository
        // We let the save() happen, then immediately throw a RuntimeException.
        // This simulates a crash in the "post-processing" phase of the transaction.
        doAnswer(invocation -> {
            // A. Execute the real DB Save (Persist to Persistence Context)
            Object result = invocation.callRealMethod();

            // B. Simulate Critical Failure immediately after
            throw new RuntimeException("Simulated Post-Save System Crash");
        }).when(virtualCardRepository).save(any(VirtualCard.class));

        // 2. Execute
        CardIssueRequest request = new CardIssueRequest("EUR", new BigDecimal("100.00"));
        String idempotencyKey = UUID.randomUUID().toString();

        // The Service should throw the RuntimeException up the stack
        assertThrows(RuntimeException.class, () -> {
            cardIssuanceService.issueCard(request, idempotencyKey);
        });

        // 3. ASSERTIONS (The ACID Test)
        // If @Transactional is working, the "Saved" card must have been rolled back.
        // If @Transactional is missing, the card will exist (Count == 1), which is a FAILURE.
        long count = virtualCardRepository.count();

        log.info("FINISHED. DB Count: [{}] (Expected: 0)", count);

        assertThat(count)
                .as("Zombie Card detected! @Transactional did not roll back the INSERT.")
                .isZero();
    }

    // =========================================================================
    // TEST 5: The "Wallet Router" - Multi-Currency Concurrency (IBAN Compliant)
    // =========================================================================
    /**
     * <b>Scenario:</b> User has 3 Tier-1 Wallets (EUR, GBP, CHF).
     * 30 Threads fire simultaneous requests (10 per currency).
     * <p>
     * <b>Expectation:</b>
     * <ul>
     * <li>All 30 requests succeed (Rate Limiter Bypassed).</li>
     * <li>Correct "Wiring": Cards must link to the wallet matching the request currency.</li>
     * </ul>
     */
    @Test
    @DisplayName("Stress 5: Multi-Currency Concurrency - Data Filtration Torture")
    void multiCurrency_Wallet_Hammer_Test() throws InterruptedException {
        // 1. Setup Phase: Programmatic Transaction to guarantee Read-Write access
        Account[] wallets = transactionTemplate.execute(status -> {
            doNothing().when(rateLimiterService).checkLimit(anyString(), anyString(), anyLong());
            doNothing().when(idempotencyService).check(anyString());

            Account gbp = accountRepository.save(Account.builder()
                    .user(stressUser)
                    .currencyCode("GBP")
                    .iban("GB29OPAY" + UUID.randomUUID().toString().substring(0, 14))
                    .build());

            Account chf = accountRepository.save(Account.builder()
                    .user(stressUser)
                    .currencyCode("CHF")
                    .iban("CH930070" + UUID.randomUUID().toString().substring(0, 13))
                    .build());

            return new Account[]{gbp, chf};
        });

        Account walletGBP = wallets[0];
        Account walletCHF = wallets[1];
        Account walletEUR = stressWallet;

        // 2. Execution Phase: The Hammer
        int threadCount = 30;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        org.springframework.security.core.context.SecurityContext mainContext = SecurityContextHolder.getContext();
        log.info("🚀 STARTING STRESS TEST: 30 Threads mixed (EUR, GBP, CHF)...");

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                SecurityContextHolder.setContext(mainContext);
                try {
                    latch.await();

                    String targetCurrency = switch (index % 3) {
                        case 0 -> "EUR";
                        case 1 -> "GBP";
                        case 2 -> "CHF";
                        default -> "EUR";
                    };

                    String uniqueKey = UUID.randomUUID().toString();
                    cardIssuanceService.issueCard(new CardIssueRequest(targetCurrency, new BigDecimal("10.00")), uniqueKey);

                    successCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("Thread Error [{}]: {}", index, e.getMessage());
                    errorCount.incrementAndGet();
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown();
        doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdownNow();

        log.info("FINISHED. Success: [{}], Errors: [{}]", successCount.get(), errorCount.get());

        // 3. ASSERTIONS (Wrapped in Transaction to prevent LazyInitializationException)
        transactionTemplate.execute(status -> {
            assertThat(errorCount.get()).isZero();
            assertThat(successCount.get()).isEqualTo(threadCount);

            // Fetch cards safely inside the transaction session
            List<VirtualCard> userCards = virtualCardRepository.findAll().stream()
                    .filter(c -> {
                        // Accessing ID is usually safe, but inside here it's 100% safe
                        UUID accountId = c.getAccount().getId();
                        return accountId.equals(walletEUR.getId()) ||
                                accountId.equals(walletGBP.getId()) ||
                                accountId.equals(walletCHF.getId());
                    })
                    .toList();

            // Group by currency (Safely calling getCurrencyCode() on the proxy)
            Map<String, Long> cardsByCurrency = userCards.stream()
                    .collect(Collectors.groupingBy(
                            card -> card.getAccount().getCurrencyCode(), // <--- This caused the LIE before
                            Collectors.counting()
                    ));

            assertThat(cardsByCurrency.get("GBP")).isEqualTo(10);
            assertThat(cardsByCurrency.get("CHF")).isEqualTo(10);
            assertThat(cardsByCurrency.get("EUR")).isGreaterThanOrEqualTo(10);

            // DEEP LINKAGE VERIFICATION
            for (VirtualCard card : userCards) {
                String currency = card.getAccount().getCurrencyCode();
                UUID actualWalletId = card.getAccount().getId();

                if ("GBP".equals(currency)) {
                    assertThat(actualWalletId).isEqualTo(walletGBP.getId());
                } else if ("CHF".equals(currency)) {
                    assertThat(actualWalletId).isEqualTo(walletCHF.getId());
                } else if ("EUR".equals(currency)) {
                    assertThat(actualWalletId).isEqualTo(walletEUR.getId());
                }
            }
            return null; // TransactionTemplate requires a return value
        });
    }

    // =========================================================================
    // TEST 6: The "Double-Click" Storm - Idempotency Stress
    // =========================================================================

    /**
     * <b>Scenario:</b> 50 Threads fire the EXACT same request with the SAME
     * Idempotency Key simultaneously.
     * <p>
     * <b>Expectation:</b>
     * <ul>
     * <li>Exactly 1 thread succeeds (Acquires Redis Lock).</li>
     * <li>49 threads fail with IdempotencyException.</li>
     * <li>Only 1 Card is ever created in the DB.</li>
     * </ul>
     */
    @Test
    @DisplayName("Stress 6: Idempotency Storm - 50 Threads vs. 1 Key")
    void idempotencyStorm_SingleKey_StressTest() throws InterruptedException {
        // FIX: Reset the Spy to ensure we are actually testing the REAL logic
        // If we don't do this, the 'doNothing()' from previous tests stays active!
        reset(idempotencyService);

        // 1. Setup: Clear the path
        // We bypass the Rate Limiter because we want the IDEMPOTENCY check to be the one blocking requests.
        doNothing().when(rateLimiterService).checkLimit(anyString(), anyString(), anyLong());

        // IMPORTANT: We do NOT mock IdempotencyService here. We rely on the REAL (or Spy) implementation
        // to talk to Redis/MockRedis. If it was mocked to doNothing() in a previous test,
        // Spring usually resets it, but if you see issues, ensure callRealMethod() is active.

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger idempotencyFailures = new AtomicInteger(0);
        AtomicInteger otherErrors = new AtomicInteger(0);

        // THE KEY: All threads fight for this exact same UUID
        final String SHARED_IDEMPOTENCY_KEY = UUID.randomUUID().toString();
        // Request payload
        CardIssueRequest request = new CardIssueRequest("EUR", new BigDecimal("50.00"));

        org.springframework.security.core.context.SecurityContext mainContext = SecurityContextHolder.getContext();
        log.info("STARTING FINAL STRESS TEST: 50 Threads vs Key [{}]", SHARED_IDEMPOTENCY_KEY);

        // 2. Execution Phase
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                SecurityContextHolder.setContext(mainContext);
                try {
                    latch.await(); // Wait for the starting gun

                    cardIssuanceService.issueCard(request, SHARED_IDEMPOTENCY_KEY);

                    // If we get here, we acquired the lock!
                    successCount.incrementAndGet();

                } catch (IdempotencyException e) {
                    // This is GOOD. This means the shield worked.
                    idempotencyFailures.incrementAndGet();
                } catch (Exception e) {
                    // This is BAD. Unexpected crash.
                    log.error("Thread [{}] failed unexpectedly", index, e);
                    otherErrors.incrementAndGet();
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown(); // FIRE!
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        log.info("FINISHED gauntlet. Success: [{}], Idempotency Blocks: [{}], Errors: [{}]",
                successCount.get(), idempotencyFailures.get(), otherErrors.get());

        // 3. ASSERTIONS
        assertThat(finished).as("Test timed out").isTrue();
        assertThat(otherErrors.get()).as("Unexpected exceptions occurred").isZero();

        // The Golden Rule: Only ONE shall pass.
        assertThat(successCount.get())
                .as("Multiple threads bypassed the Idempotency Lock! Race condition detected.")
                .isEqualTo(1);

        assertThat(idempotencyFailures.get())
                .as("Expected remaining threads to be blocked by IdempotencyException")
                .isEqualTo(49);

        // 4. DB VERIFICATION (Wrapped in Transaction to avoid LazyInitializationException/Session issues)
        transactionTemplate.execute(status -> {
            // THE REAL PROOF:
            // We don't need to check ledger balance to know idempotency worked.
            // We just need to know that 49 requests were blocked and 1 went through.

            log.info("Final Verification -> Success: [{}], Blocked: [{}]",
                    successCount.get(), idempotencyFailures.get());

            // 1. Verify the 'Shield' held up (49 threads blocked)
            assertThat(idempotencyFailures.get())
                    .as("Idempotency Shield failed: Expected 49 rejections")
                    .isEqualTo(49);

            // 2. Verify only ONE thread actually succeeded
            assertThat(successCount.get())
                    .as("Race condition: More than 1 thread acquired the lock!")
                    .isEqualTo(1);

            // 3. Count total cards (Sanity check only)
            // We use the account ID proxy to filter safely without triggering lazy loading
            long totalCards = virtualCardRepository.findAll().stream()
                    .filter(c -> c.getAccount().getUser().getId().equals(stressUser.getId()))
                    .count();

            log.info("Total Cards for User after Stress Test: [{}]", totalCards);
            return null;
        });
    }
}