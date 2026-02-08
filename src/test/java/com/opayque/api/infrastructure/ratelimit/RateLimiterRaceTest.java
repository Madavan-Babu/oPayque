package com.opayque.api.infrastructure.ratelimit;

import com.opayque.api.infrastructure.exception.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * Stress-level concurrency test harness validating Redis atomicity under extreme load.
 * <p>
 * Simulates the “Ticketmaster scenario” where 50 threads attempt to access a 10-request-per-minute
 * endpoint simultaneously.  The test proves that Redis INCR operations remain atomic, ensuring
 * that exactly 10 requests succeed and 40 are rejected—eliminating race-condition “lost updates”
 * that would otherwise breach our fraud-prevention velocity limits.
 * </p>
 * <ul>
 *   <li><b>Author:</b> Madavan Babu</li>
 *   <li><b>Version:</b> 2.0.0</li>
 *   <li><b>Since:</b> 2026</li>
 * </ul>
 *
 * @see RateLimiterService
 * @see RedisTemplate
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class RateLimiterRaceTest {

    /**
     * Redis container spun up via Testcontainers to provide an isolated, deterministic
     * datastore for atomicity verification.  Uses the lightweight alpine variant for
     * rapid startup while preserving full compatibility with production Redis 7.x
     * clustering and ACL security models.
     */
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    /**
     * Injects Redis connection details into Spring’s environment so that the embedded
     * Redis instance is used exclusively during the test lifecycle.  Prevents
     * cross-contamination with shared dev/staging clusters and guarantees
     * deterministic test results.
     *
     * @param registry Spring’s dynamic property registry
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    /**
     * Resets Redis state before every test run, ensuring a clean slate and removing
     * any residual keys or TTLs that could skew concurrency metrics.  Acts as the
     * financial “end-of-day” closeout for deterministic test hygiene.
     */
    @BeforeEach
    void setup() {
        // FLUSH: Ensure a clean slate before the race begins.
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    /**
     * Validates that the rate-limiter remains mathematically precise under a 50-thread
     * thundering-herd attack.  Uses a {@link CountDownLatch} to synchronize nanosecond-level
     * request bursts and atomic counters to track success/failure ratios.  The test
     * enforces FinTech-grade correctness: exactly 10 requests succeed, 40 are blocked,
     * and zero overages occur—eliminating fraudulent transaction replay windows.
     *
     * @throws InterruptedException if thread coordination fails
     */
    @Test
    @DisplayName("Concurrency: 50 Threads vs. Limit of 10 (The Ticketmaster Scenario)")
    void shouldEnforceLimitUnderRaceConditions() throws InterruptedException {
        // 1. The Setup: 50 Concurrent Spammers
        int threadCount = 50;
        int allowedLimit = 10;
        String userId = "race-condition-user@opayque.com";

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // The "Starting Gun" - Holds all threads back until we say "GO"
        CountDownLatch startGun = new CountDownLatch(1);

        // The "Finish Line" - Waits for all threads to finish before asserting
        CountDownLatch finishLine = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger blockedCount = new AtomicInteger(0);

        // 2. The Loading Phase
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    // Wait for the gun!
                    startGun.await();

                    // BANG! Hit the service!
                    rateLimiterService.checkLimit(userId);

                    // If no exception, we passed.
                    successCount.incrementAndGet();

                } catch (RateLimitExceededException e) {
                    // We were blocked (Correct behavior).
                    blockedCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLine.countDown();
                }
            });
        }

        // 3. The Race
        // Release the latch! All 50 threads attack Redis simultaneously.
        startGun.countDown();

        // Wait for dust to settle
        finishLine.await();
        executor.shutdown();

        // 4. The Verdict (Strict Mathematics)
        // Since we are using Redis INCR (Atomic), the count is exact.
        // No "approximately 10". EXACTLY 10.
        assertThat(successCount.get())
                .as("Exact number of allowed requests")
                .isEqualTo(allowedLimit);

        assertThat(blockedCount.get())
                .as("Exact number of blocked requests")
                .isEqualTo(threadCount - allowedLimit);
    }
}