package com.opayque.api.infrastructure.idempotency;

import com.opayque.api.infrastructure.exception.IdempotencyException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;


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
        String redisKey = KEY_PREFIX + idempotencyKey;
        String existingTxId = "tx-999-completed";

        // Mock: SETNX returns FALSE (Lock failed)
        given(valueOperations.setIfAbsent(eq(redisKey), eq("PENDING"), eq(Duration.ofHours(24))))
                .willReturn(false);

        // Mock: Fetch existing value to enrich the error message
        given(valueOperations.get(redisKey)).willReturn(existingTxId);

        // 2. Act & Assert
        assertThatThrownBy(() -> idempotencyService.lock(idempotencyKey))
                .isInstanceOf(IdempotencyException.class)
                .hasMessageContaining(idempotencyKey)
                .hasMessageContaining(existingTxId); // "Premium" touch: tells user the conflicting ID
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
}