package com.opayque.api.infrastructure.idempotency;

import com.opayque.api.infrastructure.exception.IdempotencyException;
import com.opayque.api.infrastructure.exception.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;


/**
 * Unit tests for {@link IdempotencyService}.
 * <p>
 * Validates Redis-backed idempotency guarantees:
 * <ul>
 *   <li>Successful locking of new keys</li>
 *   <li>Rejection of duplicate keys with enriched error messages</li>
 *   <li>Correct completion flow that promotes PENDING to the final TX id</li>
 *   <li>Defensive handling of null inputs</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private IdempotencyService idempotencyService;

    // Standard prefix defined in the service
    private static final String KEY_PREFIX = "idempotency:";

    /**
     * Configures common stubs once per test to avoid repetitive mocking.
     * <p>
     * Uses {@link org.mockito.Mockito#lenient() lenient} stubbing to prevent
     * {@link org.mockito.exceptions.misusing.UnnecessaryStubbingException}
     * in tests that never interact with Redis (e.g., null-input validation).
     */
    @BeforeEach
    void setUp() {
        // FIX: Use lenient() to prevent UnnecessaryStubbingException
        // because 'shouldThrowOnNullInput' fails before touching Redis.
        org.mockito.Mockito.lenient()
                .when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);
    }

    /**
     * Asserts that a brand-new idempotency key can be locked successfully.
     * <p>
     * Expects {@code SETNX} to return {@code true} and the service to return
     * {@code true}. Redis interaction is verified to ensure the TTL of 24 h
     * and the {@code PENDING} placeholder are correctly written.
     */
    @Test
    @DisplayName("Should successfully lock a new key with 'PENDING' status and 24h TTL")
    void shouldLockNewKeySuccessfully() {
        // 1. Arrange
        String idempotencyKey = "req-123-abc";
        String redisKey = KEY_PREFIX + idempotencyKey;

        // Mock: SETNX returns TRUE (Lock acquired)
        given(valueOperations.setIfAbsent(eq(redisKey), eq("PENDING"), eq(Duration.ofHours(24))))
                .willReturn(true);

        // 2. Act
        boolean result = idempotencyService.lock(idempotencyKey);

        // 3. Assert
        assertThat(result).isTrue();
        // Verify we actually sent the command to Redis
        verify(valueOperations).setIfAbsent(redisKey, "PENDING", Duration.ofHours(24));
    }

    /**
     * Validates that an attempt to lock an already-existing key results in
     * an {@link IdempotencyException} whose message contains both the
     * idempotency key <strong>and</strong> the existing transaction id
     * retrieved from Redis, giving consumers actionable diagnostics.
     */
    @Test
    @DisplayName("Should throw IdempotencyException when key already exists")
    void shouldThrowWhenKeyExists() {
        // 1. Arrange
        String idempotencyKey = "req-duplicate";
        String redisKey = "idempotency:" + idempotencyKey;
        String existingTxId = "tx-999-completed";

        // Mock: SETNX returns FALSE (Lock failed)
        given(valueOperations.setIfAbsent(eq(redisKey), eq("PENDING"), any(Duration.class)))
                .willReturn(false);

        // Mock: Fetch existing value to enrich the error message
        given(valueOperations.get(redisKey)).willReturn(existingTxId);

        // 2. Act & Assert
        // We use check() because lock() is the "Safe" version that returns boolean false.
        // We want to verify the specific message content produced by check().
        assertThatThrownBy(() -> idempotencyService.check(idempotencyKey)) // <-- CHANGE lock() to check()
                .isInstanceOf(IdempotencyException.class)
                .hasMessageContaining(existingTxId) // Verifies the "Premium" ID reporting
                .hasMessageContaining("Already processed");
    }

    /**
     * Ensures that completing an idempotent flow overwrites the {@code PENDING}
     * marker with the real transaction identifier while preserving the 24-hour TTL.
     */
    @Test
    @DisplayName("Should update key with final Transaction ID upon completion")
    void shouldCompleteTransaction() {
        // 1. Arrange
        String idempotencyKey = "req-success";
        String redisKey = KEY_PREFIX + idempotencyKey;
        String transactionId = "tx-final-777";

        // 2. Act
        idempotencyService.complete(idempotencyKey, transactionId);

        // 3. Assert
        // Verify we overwrite "PENDING" with the actual ID and refresh the TTL
        verify(valueOperations).set(redisKey, transactionId, Duration.ofHours(24));
    }

    /**
     * Confirms that supplying {@code null} as an idempotency key triggers an
     * {@link IllegalArgumentException} immediately, preventing downstream
     * code from operating on an invalid cache key.
     */
    @Test
    @DisplayName("Should handle null keys gracefully (Defensive Coding)")
    void shouldThrowOnNullInput() {
        assertThatThrownBy(() -> idempotencyService.lock(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency key cannot be null");
    }

    /// Scenario: Infrastructure Failure (Fail Closed).
    ///
    /// Validates that if the Lock Service (Redis) is unreachable, the system
    /// ABORTS the transaction (Fail Closed) rather than proceeding without a lock.
    /// This prevents "Split Brain" or double-spending during outages.
    @Test
    @DisplayName("Lock: Should FAIL CLOSED (503) when Redis is unreachable")
    void shouldFailClosedWhenRedisIsDown() {
        // Arrange
        String key = "txn-123";
        // Simulate Redis Connection Death
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));

        // Act & Assert
        // MUST throw ServiceUnavailableException (503), NOT generic Exception
        assertThatThrownBy(() -> idempotencyService.lock(key))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("Service temporarily unavailable.");
    }

    /// Scenario: Completion Failure (Fail Open).
    ///
    /// Validates that if Redis dies *after* the transaction is done (during the update to "COMPLETED"),
    /// we simply log it and move on. We do NOT rollback the successful transaction.
    /// The TTL will eventually expire the key anyway.
    @Test
    @DisplayName("Complete: Should SWALLOW exception if Redis update fails (Non-Critical)")
    void shouldLogAndContinueIfCompleteFails() {
        // Arrange
        String key = "txn-123";
        String txId = UUID.randomUUID().toString();

        // Simulate Redis Death during the "Mark as Completed" phase
        doThrow(new RedisConnectionFailureException("Connection lost"))
                .when(valueOperations).set(anyString(), eq(txId), any(Duration.class));

        // Act
        // This should NOT throw an exception.
        assertDoesNotThrow(() -> idempotencyService.complete(key, txId));

        // Assert
        // We verified it didn't crash. Ideally, we would verify the log,
        // but verifying no exception is sufficient for "Fail Open" logic.
    }

    @Test
    @DisplayName("Lock: Should catch IdempotencyException and return false on collision")
    void shouldReturnFalse_WhenLockCollisionOccurs() {
        // 1. Arrange
        String key = "duplicate-lock-request";
        String redisKey = "idempotency:" + key; // Matches your KEY_PREFIX logic

        // Mock: SETNX returns false (Lock is held by another process)
        given(valueOperations.setIfAbsent(eq(redisKey), eq("PENDING"), any(Duration.class)))
                .willReturn(false);

        // Mock: Get returns an existing transaction ID (Triggering the IdempotencyException inside check())
        given(valueOperations.get(redisKey)).willReturn("tx-existing-123");

        // 2. Act
        boolean acquired = idempotencyService.lock(key);

        // 3. Assert
        // This confirms we entered the 'catch (IdempotencyException e)' block
        // and executed the 'return false' line.
        assertThat(acquired)
                .as("Should return false instead of throwing exception")
                .isFalse();
    }
}