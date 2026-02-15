package com.opayque.api.card.service.stress;

import com.opayque.api.card.entity.CardStatus;
import com.opayque.api.card.entity.VirtualCard;
import com.opayque.api.card.repository.VirtualCardRepository;
import com.opayque.api.card.service.CardIssuanceService;
import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

/**
 * Story 4.4: Card Limit Update Stress Tests.
 * <p>
 * Validates the resiliency of the Limit Management subsystem under "Death Star" load.
 * <p>
 * <b>Scope:</b>
 * 1. Rate Limiting (The "Limit Spam" Torture).
 * 2. Idempotency (The "Stuttering Finger" Storm).
 * 3. Optimistic Locking (The "Lost Update" Race).
 * <p>
 * <b>Infrastructure:</b>
 * Runs against isolated Dockerized Postgres 15 and Redis 7 containers.
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("test")
@Tag("stress")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CardLimitUpdateStressTest {

    // =========================================================================
    // 1. INFRASTRUCTURE (Testcontainers)
    // =========================================================================
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("opayque_stress_limit")
            .withUsername("stress")
            .withPassword("stress");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(org.testcontainers.utility.DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Postgres Connection
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);

        // Force Postgres Dialect (Critical for PESSIMISTIC_WRITE / Skip Locked)
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");

        // Liquibase & Hibernate Validation
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.liquibase.url", postgres::getJdbcUrl);
        registry.add("spring.liquibase.user", postgres::getUsername);
        registry.add("spring.liquibase.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        // Redis Connection
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    // =========================================================================
    // 2. WIRING & SPIES
    // =========================================================================
    @Autowired private CardIssuanceService cardIssuanceService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private VirtualCardRepository virtualCardRepository;
    @Autowired private LedgerRepository ledgerRepository; // For cleanup

    // Spies allow us to selectively disable logic (like rate limits) in specific tests
    @MockitoSpyBean private RateLimiterService rateLimiterService;
    @MockitoSpyBean private IdempotencyService idempotencyService;

    // Test Data
    private User stressUser;
    private VirtualCard stressCard;

    @BeforeEach
    void setUp() {
        // 1. Clean Slate (Reverse Dependency Order)
        virtualCardRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
        ledgerRepository.deleteAll();
        // Note: Redis is intentionally NOT flushed here to simulate persistent bucket state,
        // but we generate a NEW User for each test to ensure a fresh Rate Limit bucket.

        // 2. Setup Data (Fresh User = Fresh Bucket)
        stressUser = userRepository.save(User.builder()
                .email("limit_stress_" + UUID.randomUUID() + "@opayque.com")
                .password("hashed_pw")
                .role(Role.CUSTOMER)
                .fullName("Limit Stress Tester")
                .build());

        Account wallet = accountRepository.save(Account.builder()
                .user(stressUser)
                .currencyCode("USD")
                .iban("US_STRESS_" + UUID.randomUUID().toString().substring(0, 8))
                .build());

        stressCard = virtualCardRepository.save(VirtualCard.builder()
                .account(wallet)
                .pan("4111" + UUID.randomUUID().toString().substring(0, 12)) // Unique PAN
                .cvv("123")
                .expiryDate("12/30")
                .cardholderName("Stress Owner")
                .status(CardStatus.ACTIVE)
                .monthlyLimit(new BigDecimal("1000.00"))
                .build());

        // 3. Inject Security Context for Service Layer
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(stressUser, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        );
    }

    // =========================================================================
    // TEST 1: The "Limit Spam" Torture (Rate Limiting)
    // =========================================================================

    /**
     * <b>Scenario:</b> 50 Threads hammer the limit update endpoint for the SAME card.
     * <p>
     * <b>Configuration:</b>
     * - Service Limit: 5 per minute (Bucket: "card_limit_update").
     * - Traffic: 50 requests in < 100ms.
     * - Idempotency: BYPASSED (Unique keys) to force load onto the Rate Limiter.
     * <p>
     * <b>Expectation:</b>
     * <ul>
     * <li>Exactly 5 threads succeed (Status 200).</li>
     * <li>Exactly 45 threads fail with {@link RateLimitExceededException}.</li>
     * <li>Redis Atomic Counters must verify this exact ratio.</li>
     * </ul>
     */
    @Test
    @DisplayName("Stress 1: Limit Spam - 50 Threads vs Redis Bucket (5/min)")
    void concurrentLimitUpdate_RateLimit_TortureTest() throws InterruptedException {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1); // The "Gun"
        CountDownLatch doneLatch = new CountDownLatch(threadCount); // The "Finish Line"

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger blockedCount = new AtomicInteger(0);
        AtomicInteger otherErrors = new AtomicInteger(0);

        // Capture Main Thread Context (Authentication)
        org.springframework.security.core.context.SecurityContext mainContext = SecurityContextHolder.getContext();
        UUID cardId = stressCard.getId();

        log.info("STARTING STRESS TEST 1: 50 Threads attempting Limit Update...");

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                // Propagate Auth
                SecurityContextHolder.setContext(mainContext);
                try {
                    latch.await(); // Wait for the signal

                    // UNIQUE Key per thread -> Bypasses Idempotency, hits Rate Limiter
                    String uniqueKey = UUID.randomUUID().toString();
                    BigDecimal newLimit = new BigDecimal("5000.00");

                    // The Service Call
                    cardIssuanceService.updateMonthlyLimit(cardId, newLimit, uniqueKey);

                    successCount.incrementAndGet();

                } catch (RateLimitExceededException e) {
                    blockedCount.incrementAndGet(); // Expected behavior for 45 threads
                } catch (Exception e) {
                    log.error("Unexpected error in stress test", e);
                    otherErrors.incrementAndGet();
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown(); // FIRE ALL THREADS
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        log.info("FINISHED. Success: [{}], Blocked: [{}], Errors: [{}]",
                successCount.get(), blockedCount.get(), otherErrors.get());

        // ASSERTIONS
        // Now that we use Pessimistic Locking, all 5 allowed threads should succeed
        // sequentially without "Stale State" errors.
        assertThat(successCount.get())
                .as("Expected exactly 5 successful updates (The Rate Limit quota).")
                .isEqualTo(5);

        assertThat(blockedCount.get())
                .as("Expected 45 requests to be blocked by the Rate Limiter gate.")
                .isEqualTo(45);

        assertThat(otherErrors.get()).isZero();
    }

    // =========================================================================
    // TEST 2: The "Stuttering Finger" Storm (Idempotency)
    // =========================================================================

    /**
     * <b>Scenario:</b> 50 Threads fire the EXACT same request with the SAME
     * Idempotency Key simultaneously.
     * <p>
     * <b>Configuration:</b>
     * - Rate Limiting: DISABLED (Mocked to allow all) to isolate Idempotency logic.
     * - Traffic: 50 requests in < 100ms.
     * - Key: SHARED (Identical UUID).
     * <p>
     * <b>Expectation:</b>
     * <ul>
     * <li>Exactly 1 thread succeeds (Acquires Redis Lock).</li>
     * <li>Exactly 49 threads fail with {@link com.opayque.api.infrastructure.exception.IdempotencyException}.</li>
     * <li>The DB is updated exactly once to the new value.</li>
     * </ul>
     */
    @Test
    @DisplayName("Stress 2: Idempotency Storm - 50 Threads vs 1 Key")
    void concurrentLimitUpdate_Idempotency_TortureTest() throws InterruptedException {
        // 1. DISABLE Rate Limiter for this test
        // We want to prove that IDEMPOTENCY blocks the duplicates, not the Rate Limiter.
        doNothing().when(rateLimiterService).checkLimit(anyString(), anyString(), anyLong());

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger blockedCount = new AtomicInteger(0);
        AtomicInteger otherErrors = new AtomicInteger(0);

        // SHARED Data
        String sharedKey = UUID.randomUUID().toString();
        BigDecimal newLimit = new BigDecimal("8888.00");
        UUID cardId = stressCard.getId();
        org.springframework.security.core.context.SecurityContext mainContext = SecurityContextHolder.getContext();

        log.info("STARTING STRESS TEST 2: 50 Threads vs Single Key [{}]", sharedKey);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                SecurityContextHolder.setContext(mainContext);
                try {
                    latch.await(); // Hold...

                    // FIRE! Same Card, Same Amount, Same Key
                    cardIssuanceService.updateMonthlyLimit(cardId, newLimit, sharedKey);

                    successCount.incrementAndGet();

                } catch (com.opayque.api.infrastructure.exception.IdempotencyException e) {
                    // GOOD: The lock worked
                    blockedCount.incrementAndGet();
                } catch (Exception e) {
                    // BAD: RateLimitExceeded or RuntimeException
                    log.error("Unexpected error", e);
                    otherErrors.incrementAndGet();
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown(); // RELEASE THE STORM
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        log.info("FINISHED. Success: [{}], Idempotency Blocks: [{}], Errors: [{}]",
                successCount.get(), blockedCount.get(), otherErrors.get());

        // ASSERTIONS
        assertThat(finished).as("Test timed out").isTrue();
        assertThat(otherErrors.get()).as("Unexpected exceptions occurred").isZero();

        // The Golden Rule: Only ONE shall pass.
        assertThat(successCount.get())
                .as("Race Condition! More than 1 thread bypassed the Idempotency Lock.")
                .isEqualTo(1);

        assertThat(blockedCount.get())
                .as("Shield Failure! Expected remaining threads to be blocked.")
                .isEqualTo(49);

        // Verify DB State (Should be updated to the new limit)
        VirtualCard card = virtualCardRepository.findById(cardId).orElseThrow();
        assertThat(card.getMonthlyLimit()).isEqualByComparingTo(newLimit);
    }

    // =========================================================================
    // TEST 3: The "Lost Update" Race (Optimistic Locking)
    // =========================================================================

    /**
     * <b>Scenario:</b> 50 Threads try to update the limit to DIFFERENT values
     * on the SAME card simultaneously.
     * <p>
     * <b>Configuration:</b>
     * - Rate Limiting: DISABLED (Mocked).
     * - Idempotency: DISABLED (Mocked).
     * - Traffic: 50 requests in < 100ms.
     * <p>
     * <b>Expectation:</b>
     * <ul>
     * <li>Exactly 1 thread succeeds (First Commit Wins).</li>
     * <li>Exactly 49 threads fail with {@link org.springframework.orm.ObjectOptimisticLockingFailureException}.</li>
     * <li>The DB value matches the "Winner", not a random interleaved state.</li>
     * </ul>
     */
    @Test
    @DisplayName("Stress 3: Lost Update Race - 50 Threads vs DB Version")
    void concurrentLimitUpdate_OptimisticLocking_TortureTest() throws InterruptedException {
        // 1. DISABLE Outer Defenses to expose the DB
        doNothing().when(rateLimiterService).checkLimit(anyString(), anyString(), anyLong());
        doNothing().when(idempotencyService).check(anyString());
        // Note: Idempotency completion might throw if we don't mock it, but since we expect
        // 49 threads to fail at the DB layer, they won't reach completion.
        // For the winner, we mock completion to be safe.
        doNothing().when(idempotencyService).complete(anyString(), anyString());

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger optimisticLockFailures = new AtomicInteger(0);
        AtomicInteger otherErrors = new AtomicInteger(0);

        UUID cardId = stressCard.getId();
        org.springframework.security.core.context.SecurityContext mainContext = SecurityContextHolder.getContext();

        log.info("STARTING STRESS TEST 3: 50 Threads fighting over DB Row Version...");

        for (int i = 0; i < threadCount; i++) {
            // Each thread tries to set a unique limit (e.g., 100, 101, 102...)
            final BigDecimal attemptLimit = new BigDecimal("100.00").add(new BigDecimal(i));

            executor.submit(() -> {
                SecurityContextHolder.setContext(mainContext);
                try {
                    latch.await();

                    // UNIQUE Key per thread (we want them to pass the Idempotency check and hit the DB)
                    String uniqueKey = UUID.randomUUID().toString();

                    cardIssuanceService.updateMonthlyLimit(cardId, attemptLimit, uniqueKey);

                    // If we get here, we committed the transaction!
                    successCount.incrementAndGet();
                    log.info("WINNER: Limit set to {}", attemptLimit);

                } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                    // GOOD: Hibernate protected the row from a lost update
                    optimisticLockFailures.incrementAndGet();
                } catch (Exception e) {
                    // Check for wrapped Hibernate StaleState exceptions
                    if (e.getCause() instanceof org.hibernate.StaleObjectStateException) {
                        optimisticLockFailures.incrementAndGet();
                    } else {
                        log.error("Unexpected error", e);
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

        log.info("FINISHED. Success: [{}], Stale State Blocks: [{}], Errors: [{}]",
                successCount.get(), optimisticLockFailures.get(), otherErrors.get());

        // ASSERTIONS: With Pessimistic Locking, all 50 threads must succeed sequentially.
        assertThat(finished).as("Test timed out").isTrue();
        assertThat(otherErrors.get()).as("Unexpected exceptions occurred").isZero();

        // 1. Success Count must be 50 (The lock ensures no collisions/failures)
        assertThat(successCount.get())
                .as("Data Integrity Failure! Not all updates were processed sequentially.")
                .isEqualTo(50);

        // 2. Lock Failures should be 0 (They are queueing now, not failing)
        assertThat(optimisticLockFailures.get()).isZero();

        // 3. Verify Version Drift: The DB version should be exactly +50 from start
        VirtualCard finalCard = virtualCardRepository.findById(cardId).orElseThrow();
        assertThat(finalCard.getVersion()).isEqualTo(stressCard.getVersion() + 50);
    }
}