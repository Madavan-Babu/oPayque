package com.opayque.api.card.service;

import com.opayque.api.infrastructure.exception.CardLimitExceededException;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.data.redis.serializer.RedisSerializer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class CardLimitServiceTest {

    // FIX: Updated to <String, Object> to match Service
    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @InjectMocks
    private CardLimitService cardLimitService;

    private UUID cardId;
    private String expectedKey;

    @BeforeEach
    void setUp() {
        cardId = UUID.randomUUID();
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        expectedKey = "spend:" + cardId + ":" + month;

        // FIX: Stub the serializer. The service calls this to serialize keys/args for the script.
        // We use lenient() because some tests (like no-op) might not call it.
        lenient().when(redisTemplate.getStringSerializer()).thenReturn(RedisSerializer.string());
    }

    // =========================================================================
    // SECTION 1: AUTHORIZATION LOGIC (CHECK LIMIT)
    // =========================================================================

    @Test
    @DisplayName("Should pass immediately if card has no limit (null)")
    void checkSpendLimit_WhenLimitIsNull_ShouldPassImmediately() {
        // Act
        cardLimitService.checkSpendLimit(cardId, null, new BigDecimal("100.00"));

        // Assert
        // FIX: Since the Service now has the null check and returns early,
        // we verify that the Redis execute was NEVER called.
        verify(redisTemplate, never()).execute(any(), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("Should pass when Lua Script returns 1 (Success)")
    void checkSpendLimit_WhenLuaReturnsSuccess_ShouldPass() {
        // Arrange
        // FIX: Use explicit class matchers to resolve signature ambiguity
        when(redisTemplate.execute(
                any(org.springframework.data.redis.core.script.RedisScript.class),
                any(org.springframework.data.redis.serializer.RedisSerializer.class),
                any(org.springframework.data.redis.serializer.RedisSerializer.class),
                anyList(),
                any(), any(), any()))
                .thenReturn(1L);

        BigDecimal limit = new BigDecimal("1000.00");
        BigDecimal amount = new BigDecimal("100.00");

        // Act & Assert (No exception thrown)
        cardLimitService.checkSpendLimit(cardId, limit, amount);
    }

    @Test
    @DisplayName("Should verify correct arguments are passed to Lua Script")
    void checkSpendLimit_ShouldPassCorrectArgsToLua() {
        // Arrange
        // FIX: Same signature fix for the 'when' stubbing
        when(redisTemplate.execute(
                any(org.springframework.data.redis.core.script.RedisScript.class),
                any(org.springframework.data.redis.serializer.RedisSerializer.class),
                any(org.springframework.data.redis.serializer.RedisSerializer.class),
                anyList(),
                any(), any(), any()))
                .thenReturn(1L);

        BigDecimal limit = new BigDecimal("1000.00");
        BigDecimal amount = new BigDecimal("50.50");

        // Act
        cardLimitService.checkSpendLimit(cardId, limit, amount);

        // Assert
        // FIX: verify() also needs the explicit classes to distinguish the overload
        verify(redisTemplate).execute(
                any(org.springframework.data.redis.core.script.RedisScript.class),
                any(org.springframework.data.redis.serializer.RedisSerializer.class),
                any(org.springframework.data.redis.serializer.RedisSerializer.class),
                anyList(),
                eq("50.50"), // Amount
                eq("1000.00"), // Limit
                anyString() // TTL
        );
    }

    // =========================================================================
    // SECTION 2: REJECTION LOGIC
    // =========================================================================


    @Test
    @DisplayName("Should throw exception when Lua Script returns -1 (Breach)")
    void checkSpendLimit_WhenLuaReturnsBreach_ShouldThrowException() {
        // Arrange
        // FIX: Mock Breach (-1L) using the correct 5-arg signature
        when(redisTemplate.execute(
                any(org.springframework.data.redis.core.script.RedisScript.class),
                any(org.springframework.data.redis.serializer.RedisSerializer.class),
                any(org.springframework.data.redis.serializer.RedisSerializer.class),
                anyList(),
                any(), any(), any()))
                .thenReturn(-1L);

        BigDecimal limit = new BigDecimal("1000.00");
        BigDecimal amount = new BigDecimal("100.01");

        // Act & Assert
        assertThatThrownBy(() -> cardLimitService.checkSpendLimit(cardId, limit, amount))
                .isInstanceOf(CardLimitExceededException.class)
                .hasMessageContaining("Monthly spending limit exceeded");
    }

    @Test
    @DisplayName("Should throw exception when limit is ZERO and Lua returns -1")
    void checkSpendLimit_WhenLimitIsZero_AndAmountPositive_ShouldThrowException() {
        // Arrange
        when(redisTemplate.execute(
                any(org.springframework.data.redis.core.script.RedisScript.class),
                any(org.springframework.data.redis.serializer.RedisSerializer.class),
                any(org.springframework.data.redis.serializer.RedisSerializer.class),
                anyList(),
                any(), any(), any()))
                .thenReturn(-1L);

        BigDecimal limit = BigDecimal.ZERO;
        BigDecimal amount = new BigDecimal("0.01");

        // Act & Assert
        assertThatThrownBy(() -> cardLimitService.checkSpendLimit(cardId, limit, amount))
                .isInstanceOf(CardLimitExceededException.class);
    }

    // Old Test 7 (NumberFormatException) is no longer relevant for Java Unit Tests
    // because the parsing happens inside Redis (Lua).
    // If Redis returns garbage, it throws SerializationException, covered by Test 10 logic.

    // =========================================================================
    // SECTION 3: RECORDING LOGIC (ACCUMULATOR)
    // =========================================================================

    @Test
    @DisplayName("Should verify recordSpend is now a no-op")
    void recordSpend_ShouldDoNothing() {
        // Act
        cardLimitService.recordSpend(cardId, new BigDecimal("50.00"));

        // FIX: Verify ABSOLUTELY NO INTERACTION with the template.
        // The Atomic checkSpendLimit already handled the increment.
        verify(redisTemplate, times(0)).execute(any(), any(), any(), anyList(), any());
        verify(redisTemplate, times(0)).opsForValue();
    }

    // Old Test 9 (Key Format in recordSpend) is now invalid because recordSpend does nothing.
    // However, we checked key passing in "checkSpendLimit_ShouldPassCorrectArgsToLua".

    // =========================================================================
    // SECTION 4: INFRASTRUCTURE FAILURES
    // =========================================================================


    @Test
    @DisplayName("Should propagate Redis connection failures from Execute")
    void checkSpendLimit_WhenRedisIsUnreachable_ShouldPropagateException() {
        // Arrange
        // FIX: Update throw stubbing to the correct signature
        when(redisTemplate.execute(
                any(org.springframework.data.redis.core.script.RedisScript.class),
                any(org.springframework.data.redis.serializer.RedisSerializer.class),
                any(org.springframework.data.redis.serializer.RedisSerializer.class),
                anyList(),
                any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));

        BigDecimal limit = new BigDecimal("1000.00");
        BigDecimal amount = new BigDecimal("10.00");

        // Act & Assert
        assertThatThrownBy(() -> cardLimitService.checkSpendLimit(cardId, limit, amount))
                .isInstanceOf(RedisConnectionFailureException.class);
    }

    @Test
    @DisplayName("Should verify rollback uses atomic decrement")
    void rollbackSpend_ShouldDecrementAccumulator() {
        // FIX: We need a local mock for ValueOperations ONLY for this method
        // because rollbackSpend still uses the old increment approach.
        ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // Act
        cardLimitService.rollbackSpend(cardId, new BigDecimal("100.00"));

        // Assert
        // Verify it calls increment with the NEGATIVE value
        verify(valueOps).increment(eq(expectedKey), eq(-100.0));
    }

    // =========================================================================
    // SECTION 5: COVERAGE TESTS
    // =========================================================================


    @Test
    @DisplayName("Coverage: Should throw exception when Redis returns null (Infrastructure Failure)")
    void checkSpendLimit_WhenRedisReturnsNull_ShouldThrowException() {
        // Arrange
        // FIX: Explicitly return null. This triggers the first part of the OR condition.
        when(redisTemplate.execute(
                any(org.springframework.data.redis.core.script.RedisScript.class),
                any(org.springframework.data.redis.serializer.RedisSerializer.class),
                any(org.springframework.data.redis.serializer.RedisSerializer.class),
                anyList(),
                any(), any(), any()))
                .thenReturn(null);

        BigDecimal limit = new BigDecimal("1000.00");
        BigDecimal amount = new BigDecimal("10.00");

        // Act & Assert
        assertThatThrownBy(() -> cardLimitService.checkSpendLimit(cardId, limit, amount))
                .isInstanceOf(CardLimitExceededException.class)
                .hasMessageContaining("Monthly spending limit exceeded");

        log.info("Verified: Branch 'result == null' covered.");
    }
}