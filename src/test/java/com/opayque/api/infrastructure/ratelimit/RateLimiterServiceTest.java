package com.opayque.api.infrastructure.ratelimit;

import com.opayque.api.infrastructure.exception.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


/**
 * Unit-test harness for {@link RateLimiterService}.
 * <p>
 * Validates the <b>sliding-window rate-limiting</b> strategy used to protect
 * high-value payment endpoints from brute-force and DDoS attacks. The suite
 * ensures that Redis-backed counters are atomically incremented, time-bucketed,
 * and correctly expired—critical for maintaining <b>transaction integrity</b>
 * and <b>regulatory SLA</b> compliance in a FinTech environment.
 * <p>
 * Security notes:
 * <ul>
 *   <li>Each user identity is isolated by a keyed namespace {@code rate:transfers:%userId%}
 *       to prevent cross-customer contamination.</li>
 *   <li>Window TTL is strictly <b>60 s</b> to align with PCI-DSS velocity checks.</li>
 *   <li>Threshold violations immediately raise {@link RateLimitExceededException}
 *       without side effects (idempotent rejections).</li>
 * </ul>
 *
 * @author Madavan Babu
 * @since 2.0.0
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private RateLimiterService rateLimiterService;

    /**
     * Configures Mockito stubs before every test.
     * <p>
     * Binds the mocked {@link ValueOperations} to the {@link RedisTemplate}
     * so that atomic increment operations can be emulated without a live Redis
     * cluster—keeping the test hermetic and deterministic.
     */
    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    /**
     * Ensures the <b>first request within a 60-second window</b> is accepted
     * and the Redis key is initialized with an <b>atomic expiration</b>.
     * <p>
     * FinTech context: this path simulates a legitimate customer initiating
     * a funds transfer after a period of inactivity. The TTL guarantees
     * that subsequent bursts are measured against the same sliding window,
     * satisfying <b>PSD2 RTS</b> velocity limits.
     */
    @Test
    @DisplayName("Should allow request and set TTL on FIRST access (Counter = 1)")
    void shouldInitializeWindowOnFirstRequest() {
        // Arrange
        String userId = "user-123";
        String key = "rate:transfers:" + userId;

        // Mock: Redis INCR returns 1 (First request)
        when(valueOperations.increment(key)).thenReturn(1L);

        // Act
        rateLimiterService.checkLimit(userId);

        // Assert
        // Verify we set the expiration because it's a new window
        verify(redisTemplate).expire(key, Duration.ofSeconds(60));
    }

    /**
     * Validates that <b>mid-window requests</b> increment the counter
     * <b>without extending</b> the expiration—preserving the original
     * sliding-window boundary.
     * <p>
     * FinTech context: prevents malicious or accidental <b>window stretching</b>
     * attacks where an attacker could otherwise keep a window alive indefinitely
     * and bypass velocity controls.
     */
    @Test
    @DisplayName("Should allow request and NOT reset TTL on subsequent access (Counter = 5)")
    void shouldIncrementWithoutResettingTtl() {
        // Arrange
        String userId = "user-456";
        String key = "rate:transfers:" + userId;

        // Mock: Redis INCR returns 5 (Mid-window)
        when(valueOperations.increment(key)).thenReturn(5L);

        // Act
        rateLimiterService.checkLimit(userId);

        // Assert
        // Verify we strictly DO NOT reset the expiration
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    /**
     * Confirms that once the <b>threshold (10 requests/60 s)</b> is breached,
     * any further calls immediately raise {@link RateLimitExceededException}
     * and <b>no TTL mutation</b> occurs—ensuring idempotent rejections.
     * <p>
     * FinTech context: this test guards against <b>credential-stuffing</b>
     * and <b>rapid microtransaction fraud</b> by locking the user out
     * for the remainder of the window, aligning with <b>AML</b> suspicious
     * activity reporting requirements.
     */
    @Test
    @DisplayName("Should BLOCK request when limit is exceeded (Counter = 11)")
    void shouldThrowWhenLimitExceeded() {
        // Arrange
        String userId = "spammer-999";
        String key = "rate:transfers:" + userId;

        // Mock: Redis INCR returns 11 (Limit is 10)
        when(valueOperations.increment(key)).thenReturn(11L);

        // Act & Assert
        assertThatThrownBy(() -> rateLimiterService.checkLimit(userId))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage("Rate limit exceeded. Try again later.");

        // Verify we didn't touch TTL on a blocked request
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    /// Scenario: Infrastructure Glitch (Fail Open).
    ///
    /// Validates that if Redis returns a NULL value (e.g., pipeline issue or
    /// specific connection state), the system logs the error but ALLOWS the
    /// traffic to proceed. This ensures Availability over strict blocking.
    @Test
    @DisplayName("CheckLimit: Should FAIL OPEN (Allow) if Redis returns NULL")
    void shouldFailOpenWhenRedisReturnsNull() {
        // Arrange
        String userId = "fail-open-user";
        String key = "rate:transfers:" + userId;

        // Force Redis to return NULL
        when(valueOperations.increment(key)).thenReturn(null);

        // Act & Assert
        // Must NOT throw exception. Traffic flows.
        assertDoesNotThrow(() -> rateLimiterService.checkLimit(userId));
    }
}