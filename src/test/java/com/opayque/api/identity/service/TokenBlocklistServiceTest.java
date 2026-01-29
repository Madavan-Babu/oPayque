package com.opayque.api.identity.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/// Epic 1: Identity - Token Kill-Switch Unit Testing
///
/// This suite validates the lifecycle of revoked JWT signatures within the
/// {@link TokenBlocklistService}. It ensures that the "Kill-Switch" correctly
/// manages Redis entries with appropriate Time-To-Live (TTL) settings.
@ExtendWith(MockitoExtension.class)
class TokenBlocklistServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private TokenBlocklistService tokenBlocklistService;

    /// Verifies that a JWT signature is successfully added to the blocklist
    /// with a TTL that matches its remaining validity.
    @Test
    @DisplayName("Unit: Should block token with TTL matching remaining validity")
    void shouldBlockTokenWithCorrectTTL() {
        // Arrange: 10-minute validity remaining
        String signature = "test-signature-123";
        Duration ttl = Duration.ofSeconds(600);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // Act
        tokenBlocklistService.blockToken(signature, ttl);

        // Assert: Confirm Redis write with correct key and duration
        verify(valueOperations).set(eq("blacklist:" + signature), eq("blocked"), eq(ttl));
    }

    /// Ensures the service fails safe by ignoring revocation attempts for
    /// tokens that have already expired.
    @Test
    @DisplayName("Unit: Should NOT block token if TTL is negative (Already Expired)")
    void shouldNotBlockAlreadyExpiredToken() {
        String signature = "expired-signature";
        tokenBlocklistService.blockToken(signature, Duration.ofSeconds(-5));

        // No interaction expected for dead tokens
        verifyNoInteractions(redisTemplate);
    }

    /// Validates that tokens with zero remaining duration are not persisted
    /// to the blocklist, optimizing Redis memory usage.
    @Test
    @DisplayName("Unit: Should NOT block token if TTL is Zero")
    void shouldNotBlockZeroTTLToken() {
        String signature = "zero-ttl-signature";
        tokenBlocklistService.blockToken(signature, Duration.ZERO);

        verifyNoInteractions(redisTemplate);
    }

    /// Confirms that the service correctly identifies a blocked signature
    /// by querying the Redis ledger.
    @Test
    @DisplayName("Unit: shouldReturnTrueForBlockedToken checks Redis existence")
    void shouldReturnTrueForBlockedToken() {
        String signature = "blocked-signature";
        when(redisTemplate.hasKey("blacklist:" + signature)).thenReturn(true);

        assertTrue(tokenBlocklistService.isBlocked(signature));
    }

    /// Ensures that valid, non-blacklisted signatures return a negative result,
    /// allowing the authentication chain to proceed.
    @Test
    @DisplayName("Unit: shouldReturnFalseForUnknownToken")
    void shouldReturnFalseForUnknownToken() {
        String signature = "valid-signature";
        when(redisTemplate.hasKey("blacklist:" + signature)).thenReturn(false);

        assertFalse(tokenBlocklistService.isBlocked(signature));
    }
}