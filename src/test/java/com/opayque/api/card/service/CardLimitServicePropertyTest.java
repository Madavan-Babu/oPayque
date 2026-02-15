package com.opayque.api.card.service;

import com.opayque.api.infrastructure.exception.CardLimitExceededException;
import net.jqwik.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked, unused")
class CardLimitServicePropertyTest {

    // Helper to setup Mocks cleanly for every property run
    private record TestContext(
            CardLimitService service,
            RedisTemplate<String, Object> redisTemplate
            // ValueOperations removed as it's no longer used for the main check logic
    ) {}

    private TestContext setup() {
        // 1. Change generics to <String, Object> to match the Service's requirement
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);

        // 2. Stub the serializer to avoid NPEs when service calls getStringSerializer()
        lenient().when(redisTemplate.getStringSerializer()).thenReturn(RedisSerializer.string());

        // 3. Return the context
        return new TestContext(new CardLimitService(redisTemplate), redisTemplate);
    }

    // =========================================================================
    // PROPERTY 1: The "Unlimited Card" Invariant
    // =========================================================================
    @Property
    @Label("1. Unlimited cards (limit=null) should allow ANY amount (Skip Lua)")
    void unlimitedCardsShouldAllowAnyAmount(
            @ForAll("validCardId") UUID cardId,
            @ForAll("hugeAmounts") BigDecimal amount
    ) {
        TestContext ctx = setup();

        // Act
        assertThatCode(() -> ctx.service.checkSpendLimit(cardId, null, amount))
                .doesNotThrowAnyException();

        // Assert: Verify Lua script was NEVER executed (Logic short-circuits)
        verify(ctx.redisTemplate, never()).execute(any(), any(), any(), anyList(), any());
    }

    // =========================================================================
    // PROPERTY 2: The "Safe Spend" Mathematics
    // =========================================================================
    @Property
    @Label("2. Transactions where Lua returns Success (1L) must ALWAYS pass")
    void transactionsWithinLimitShouldAlwaysPass(
            @ForAll("validCardId") UUID cardId,
            @ForAll("financialAmounts") BigDecimal limit,
            @ForAll("financialAmounts") BigDecimal amount
    ) {
        TestContext ctx = setup();

        // FIX: Mock the Lua script returning 1L (Success)
        lenient().when(ctx.redisTemplate.execute(
                        any(org.springframework.data.redis.core.script.RedisScript.class),
                        any(org.springframework.data.redis.serializer.RedisSerializer.class),
                        any(org.springframework.data.redis.serializer.RedisSerializer.class),
                        anyList(),
                        any(), any(), any()))
                .thenReturn(1L);

        // Act & Assert
        assertThatCode(() -> ctx.service.checkSpendLimit(cardId, limit, amount))
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // PROPERTY 3: The "Hard Stop" Mathematics
    // =========================================================================
    @Property
    @Label("3. Transactions where Lua returns Breach (-1L) must ALWAYS throw")
    void transactionsExceedingLimitShouldAlwaysThrow(
            @ForAll("validCardId") UUID cardId,
            @ForAll("financialAmounts") BigDecimal limit,
            @ForAll("financialAmounts") BigDecimal amount
    ) {
        TestContext ctx = setup();

        // FIX: Mock the Lua script returning -1L (Breach)
        lenient().when(ctx.redisTemplate.execute(
                        any(org.springframework.data.redis.core.script.RedisScript.class),
                        any(org.springframework.data.redis.serializer.RedisSerializer.class),
                        any(org.springframework.data.redis.serializer.RedisSerializer.class),
                        anyList(),
                        any(), any(), any()))
                .thenReturn(-1L);

        // Act & Assert
        assertThatThrownBy(() -> ctx.service.checkSpendLimit(cardId, limit, amount))
                .isInstanceOf(CardLimitExceededException.class);
    }

    // =========================================================================
    // PROPERTY 4: The "Precision Trap" (Correct String Argument Passing)
    // =========================================================================
    @Property
    @Label("4. High-precision amounts should be passed to Lua as Strings")
    void recordSpendShouldHandleHighPrecisionAmounts(
            @ForAll("validCardId") UUID cardId,
            @ForAll("highPrecisionAmounts") BigDecimal amount
    ) {
        TestContext ctx = setup();
        BigDecimal limit = new BigDecimal("10000.00");

        // Mock success so we can verify arguments
        lenient().when(ctx.redisTemplate.execute(
                        any(org.springframework.data.redis.core.script.RedisScript.class),
                        any(org.springframework.data.redis.serializer.RedisSerializer.class),
                        any(org.springframework.data.redis.serializer.RedisSerializer.class),
                        anyList(),
                        any(), any(), any()))
                .thenReturn(1L);

        ctx.service.checkSpendLimit(cardId, limit, amount);

        // Capture what was sent to Redis execute()
        // We expect the amount to be passed as a String to avoid JSON double precision issues
        verify(ctx.redisTemplate).execute(
                any(org.springframework.data.redis.core.script.RedisScript.class),
                any(org.springframework.data.redis.serializer.RedisSerializer.class),
                any(org.springframework.data.redis.serializer.RedisSerializer.class),
                anyList(),
                eq(amount.toString()), // CHECK 1: Amount passed as String
                eq(limit.toString()),  // CHECK 2: Limit passed as String
                anyString()
        );
    }

    // =========================================================================
    // PROPERTY 5: The "No-Op" Record Spend
    // =========================================================================
    @Property
    @Label("5. recordSpend should be a No-Op (Logic moved to Atomic Check)")
    void recordSpendShouldBeNoOp(
            @ForAll("validCardId") UUID cardId,
            @ForAll("financialAmounts") BigDecimal amount
    ) {
        TestContext ctx = setup();

        ctx.service.recordSpend(cardId, amount);

        // Verify NO interaction with Redis Template
        verifyNoInteractions(ctx.redisTemplate);
    }

    // =========================================================================
    // PROPERTY 6: Key Generation Determinism
    // =========================================================================
    @Property
    @Label("6. Rollback must use correct 'spend:uuid:yyyy-MM' key format")
    void rollbackShouldUseCorrectKeyFormat(
            @ForAll("validCardId") UUID cardId,
            @ForAll("financialAmounts") BigDecimal amount
    ) {
        TestContext ctx = setup();

        // Setup local ValueOperations mock just for rollback logic
        ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
        when(ctx.redisTemplate.opsForValue()).thenReturn(valueOps);

        // Act: Rollback
        ctx.service.rollbackSpend(cardId, amount);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).increment(keyCaptor.capture(), anyDouble());

        String key = keyCaptor.getValue();
        String currentMonth = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

        assertThat(key).startsWith("spend:" + cardId + ":" + currentMonth);
        assertThat(key).matches("spend:[a-f0-9\\-]+:\\d{4}-\\d{2}");
    }

    // =========================================================================
    // PROPERTY 7: Negative Amount Handling (Refunds/Rollbacks)
    // =========================================================================
    @Property
    @Label("7. Rollback should increment by negative amount")
    void rollbackShouldDecrementAccumulator(
            @ForAll("validCardId") UUID cardId,
            @ForAll("financialAmounts") BigDecimal amount
    ) {
        TestContext ctx = setup();

        // Setup local ValueOperations mock
        ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
        when(ctx.redisTemplate.opsForValue()).thenReturn(valueOps);

        ctx.service.rollbackSpend(cardId, amount);

        // Verify increment is called with NEGATIVE value
        verify(valueOps).increment(anyString(), eq(amount.negate().doubleValue()));
    }

    // =========================================================================
    // ARBITRARIES (DATA GENERATORS)
    // =========================================================================

    @Provide
    Arbitrary<UUID> validCardId() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Provide
    Arbitrary<BigDecimal> hugeAmounts() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("999999999999.99"))
                .ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> financialAmounts() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ONE, new BigDecimal("10000.00"))
                .ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> highPrecisionAmounts() {
        // Generate numbers like 10.123456789
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ONE, new BigDecimal("1000.00"))
                .ofScale(10);
    }
}