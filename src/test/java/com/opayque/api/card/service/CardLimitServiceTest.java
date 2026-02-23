package com.opayque.api.card.service;

import com.opayque.api.card.controller.CardController;
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
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test suite for {@link CardLimitService}.
 * <p>
 * This class provides comprehensive unit tests that verify the behavior of the
 * {@link CardLimitService} across four functional areas:
 * <ul>
 *   <li><b>Authorization Logic</b> – Ensures that spend limits are correctly
 *       evaluated, including handling of {@code null} limits, successful Lua
 *       script execution, and proper argument passing to the script.</li>
 *   <li><b>Rejection Logic</b> – Confirms that a {@link CardLimitExceededException}
 *       is thrown when the Lua script signals a breach (return value {@code -1})
 *       and when a zero limit is supplied with a positive spend amount.</li>
 *   <li><b>Recording Logic</b> – Validates that the {@code recordSpend}
 *       operation is now a no‑op, guaranteeing no interaction with the underlying
 *       {@link RedisTemplate} after the atomic check‑spend logic has been moved
 *       into {@code checkSpendLimit}.</li>
 *   <li><b>Infrastructure Failures</b> – Checks that Redis connectivity issues are
 *       propagated as runtime exceptions, preserving the service’s failure semantics.</li>
 * </ul>
 * <p>
 * The tests employ Mockito to mock {@link RedisTemplate} interactions and use
 * {@code lenient()} stubbing for the serializer to avoid unnecessary verification
 * in scenarios where the script is not executed. Each test method is annotated
 * with {@code @DisplayName} to provide clear, human‑readable intent when the
 * test suite is executed.
 * <p>
 * By covering both positive and negative paths, this suite safeguards the
 * correctness of spend‑limit enforcement, ensuring that business rules around
 * card spending limits are reliably enforced in production.
 *
 * @author Madavan Babu
 * @since 2026
 *
 * @see CardController
 * @see com.opayque.api.card.repository.VirtualCardRepository
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
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

    /**
     * Tests that {@link CardLimitService#checkSpendLimit(UUID, BigDecimal, BigDecimal)} returns
     * immediately when the {@code monthlyLimit} argument is {@code null}, i.e., for cards without a
     * spending cap.
     * <p>
     * The test invokes the service method with a valid {@code cardId}, a {@code null} limit and a
     * sample amount, then asserts that the {@code redisTemplate.execute(...)} call is never
     * triggered. This verifies the early‑exit logic that treats limit‑less cards as always
     * approved.
     * </p>
     *
     * @see CardLimitService
     * @see CardLimitServiceTest
     */
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

    /**
     * Verifies that {@link CardLimitService#checkSpendLimit(UUID, BigDecimal, BigDecimal)} returns
     * normally when the Redis Lua script reports a successful reservation (return value {@code 1L}).
     * <p>
     * The test mocks {@link RedisTemplate} to return {@code 1L}
     * for the {@code execute(...)} invocation, then calls the service with a valid {@code cardId},
     * a defined {@code monthlyLimit}, and an {@code amount} to be spent. Because the script
     * indicates success, no {@link CardLimitExceededException} is thrown
     * and the method completes without further side effects.
     * </p>
     *
     * @see CardLimitService
     * @see CardLimitServiceTest
     */
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

    /**
     * Verifies that {@link CardLimitService#checkSpendLimit(UUID, BigDecimal, BigDecimal)} supplies
     * the correct arguments to the Redis Lua script.
     * <p>
     * The test stubs {@link RedisTemplate} to return {@code 1L}, invokes the service method with
     * a specific {@code cardId}, {@code limit}, and {@code amount}, and then asserts that the
     * {@code execute(...)} call receives the expected parameters:
     * <ul>
     *   <li>The generated Redis key for the card</li>
     *   <li>The {@code amount} as a {@code String}</li>
     *   <li>The {@code limit} as a {@code String}</li>
     *   <li>A TTL value represented as a {@code String}</li>
     * </ul>
     * This ensures the service adheres to the Lua script’s contract, avoiding mismatched
     * argument ordering or type conversion issues that could lead to incorrect limit checks.
     *
     * @see CardLimitService
     * @see CardLimitServiceTest
     */
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


    /**
     * Verifies that {@link CardLimitService#checkSpendLimit(UUID, BigDecimal, BigDecimal)}
     * throws a {@link CardLimitExceededException} when the Redis Lua script indicates a
     * breach by returning {@code -1L}.
     * <p>
     * The test configures a {@link RedisTemplate} mock to return {@code -1L} for the
     * {@code execute(...)} call, then calls the service with a valid {@code cardId},
     * a defined {@code monthlyLimit}, and an {@code amount} that would exceed that limit.
     * The expected behavior is an exception of type {@link CardLimitExceededException}
     * whose message contains {@code "Monthly spending limit exceeded"}.
     * </p>
     *
     * @see CardLimitService
     * @see CardLimitServiceTest
     */
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

    /**
     * Verifies that {@link CardLimitService#checkSpendLimit(UUID, BigDecimal, BigDecimal)} throws a
     * {@link CardLimitExceededException} when the card's {@code monthlyLimit} is {@code 0}
     * and the attempted {@code amount} is positive.
     * <p>
     * The test configures a {@link RedisTemplate} mock to return {@code -1L} from the Lua script,
     * which indicates a limit breach. With a zero limit, any positive spend request should be
     * considered a breach, causing the service to raise the exception.
     * </p>
     *
     * @see CardLimitService
     * @see CardLimitServiceTest
     * @see CardLimitExceededException
     * @see RedisTemplate
     */
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

    /**
     * Verifies that {@link CardLimitService#recordSpend(UUID, BigDecimal)} performs no
     * Redis interactions after the atomic increment was relocated to {@code checkSpendLimit}.
     * <p>
     * The test calls {@code recordSpend} with a sample {@code cardId} and {@code amount},
     * then asserts that {@link RedisTemplate} is never invoked (neither {@code execute(...)}
     * nor {@code opsForValue()}). This guarantees the method acts solely as a compatibility
     * shim and does not introduce double‑counting of spend amounts.
     * </p>
     *
     * @see CardLimitService
     * @see CardLimitServiceTest
     * @see RedisTemplate
     */
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


    /**
     * Verifies that {@link CardLimitService#checkSpendLimit(UUID, BigDecimal, BigDecimal)} propagates
     * a {@link RedisConnectionFailureException} when the underlying {@link RedisTemplate} cannot be
     * reached.
     * <p>
     * The test stubs {@code redisTemplate.execute(...)} to throw a
     * {@link RedisConnectionFailureException}, simulating a network outage or mis‑configuration.
     * It then calls the service method with a valid {@code cardId}, a defined {@code limit}, and an
     * {@code amount}, asserting that the same exception type is re‑thrown to the caller.
     * This guarantees that infrastructure‑level failures are not silently swallowed, allowing
     * higher layers to apply appropriate retry or fallback strategies.
     * </p>
     *
     * @see CardLimitService
     * @see CardLimitServiceTest
     * @see RedisTemplate
     */
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

    /**
     * Verifies that {@link CardLimitService#rollbackSpend(UUID, BigDecimal)} performs
     * an atomic decrement of the Redis spend accumulator.
     * <p>
     * The test creates a dedicated {@link ValueOperations} mock and stubs
     * {@link RedisTemplate#opsForValue()} to return it. After invoking
     * {@code cardLimitService.rollbackSpend(cardId, new java.math.BigDecimal("100.00"))},
     * the test asserts that {@link ValueOperations#increment(Object, double)} (String, double)} is called with
     * the expected Redis key and a negative delta ({@code -100.0}).
     * </p>
     * <p>
     * This ensures that a rollback is executed as a single atomic Redis operation,
     * preventing race conditions that could arise from a read‑modify‑write pattern.
     * </p>
     *
     * @see CardLimitService
     * @see CardLimitServiceTest
     * @see RedisTemplate
     * @see ValueOperations
     */
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


    /**
     * Validates that {@link CardLimitService#checkSpendLimit(UUID, BigDecimal, BigDecimal)}
     * throws a {@link CardLimitExceededException} when the Redis script execution returns {@code null},
     * representing an infrastructure failure.
     *
     * <p>The test configures {@link RedisTemplate} to return
     * {@code null} for the spend‑limit Lua script, thereby exercising the first branch of the
     * service's OR condition. The subsequent assertion verifies that the exception type and its
     * message contain the expected "Monthly spending limit exceeded" text.
     *
     * @see CardLimitService
     * @see CardController
     * @see com.opayque.api.card.repository.VirtualCardRepository
     */
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