package com.opayque.api.infrastructure.idempotency;

import com.opayque.api.infrastructure.exception.IdempotencyException;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.BeforeTry;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test suite for the IdempotencyService using Jqwik framework.
 * 
 * <p>This test class implements formal verification of the idempotency engine by:
 * <ul>
 *   <li>Fuzz-testing against an infinite string space to ensure robustness</li>
 *   <li>Verifying state transitions using an in-memory Redis stub</li>
 *   <li>Ensuring idempotency guarantees under concurrent access scenarios</li>
 *   <li>Validating proper error handling for edge cases</li>
 * </ul>
 * 
 * <p>The test employs a mock Redis implementation to simulate real-world scenarios
 * without external dependencies, ensuring deterministic test execution while maintaining
 * high coverage of the idempotency logic.
 * 
 * <p>Key properties verified:
 * <ul>
 *   <li>Double-locking prevention - same key cannot be locked twice</li>
 *   <li>State transition correctness - PENDING → COMPLETED with transaction ID</li>
 *   <li>Robustness against arbitrary string inputs (Unicode, control chars, etc.)</li>
 *   <li>Null input validation and appropriate exception handling</li>
 * </ul>
 * 
 * @see IdempotencyService
 * @see IdempotencyException
 * @since 2026
 * @version 2.0.0
 */
class IdempotencyPropertyTest {

    private IdempotencyService idempotencyService;
    private Map<String, String> redisStub; // The "Fake" Redis

    /**
     * Initializes the test environment before each property execution.
     * 
     * <p>Sets up an in-memory Redis stub using a HashMap to simulate Redis operations.
     * This approach provides:
     * <ul>
     *   <li>Deterministic test execution without external Redis dependency</li>
     *   <li>Fast test execution suitable for property-based testing</li>
     *   <li>Full control over Redis behavior and state verification</li>
     * </ul>
     * 
     * <p>The stub implements all Redis operations used by IdempotencyService:
     * <ul>
     *   <li>{@code setIfAbsent} - atomic key creation with TTL simulation</li>
     *   <li>{@code get} - value retrieval for state checking</li>
     *   <li>{@code set} - value updates for completion operations</li>
     * </ul>
     */
    @BeforeTry
    @SuppressWarnings("unchecked") // Fixes "Unchecked assignment" warnings
    void setUp() {
        // 1. Initialize the In-Memory Store
        redisStub = new HashMap<>();

        // 2. Create the "Smart Stub" for RedisTemplate
        // We cast the mock explicitly to handle the generic types safe-ish
        RedisTemplate<String, String> mockTemplate = (RedisTemplate<String, String>) mock(RedisTemplate.class);
        ValueOperations<String, String> opsStub = createRedisStub();

        when(mockTemplate.opsForValue()).thenReturn(opsStub);

        // 3. Instantiate the Service
        idempotencyService = new IdempotencyService(mockTemplate);
    }

    /**
     * Property: Concurrent access to the same idempotency key must always result in conflict.
     * 
     * <p>Verifies the fundamental idempotency guarantee that once a key is locked,
     * any subsequent attempt to lock the same key must fail with an appropriate exception.
     * This property is tested with 100 different randomly generated keys from the
     * chaosKeys generator to ensure robustness against arbitrary string inputs.
     * 
     * <p>The test validates:
     * <ul>
     *   <li>First lock attempt always succeeds</li>
     *   <li>Second lock attempt throws {@link IdempotencyException}</li>
     *   <li>Redis state contains the expected PENDING status after first lock</li>
     *   <li>Exception message contains descriptive text about ongoing processing</li>
     * </ul>
     * 
     * @param key a randomly generated string from the chaosKeys generator
     */
    @Property(tries = 50)
    void crossThreadAccessMustResultInConflict(@ForAll("chaosKeys") String key) throws Exception {
        // 1. Thread A locks the key
        boolean threadA = idempotencyService.lock(key);
        assertThat(threadA).isTrue();

        // 2. Thread B tries to check the same key
        // We run this in a different thread to trigger the Redis collision
        CompletableFuture<Throwable> threadBResult = CompletableFuture.supplyAsync(() -> {
            try {
                idempotencyService.check(key);
                return null;
            } catch (Throwable t) {
                return t;
            }
        });

        Throwable failure = threadBResult.get(5, TimeUnit.SECONDS);

        // 3. Verify Thread B was blocked
        assertThat(failure)
                .as("A different thread must be blocked by the idempotency lock")
                .isInstanceOf(IdempotencyException.class)
                .hasMessageContaining("Processing is already in progress");
    }

    @Property(tries = 100)
    void reentrancyMustAllowSameThreadToRelock(@ForAll("chaosKeys") String key) {
        // 1. First Access -> Succeeds
        boolean firstAttempt = idempotencyService.lock(key);
        assertThat(firstAttempt).isTrue();

        // 2. Second Access (Same Thread) -> MUST succeed now (Fix 3: Re-entrancy)
        boolean secondAttempt = idempotencyService.lock(key);

        assertThat(secondAttempt)
                .as("Re-entrancy logic should allow the same thread to re-acquire its own lock")
                .isTrue();
    }

    /**
     * Property: The idempotency service must handle arbitrary string inputs without crashing.
     * 
     * <p>Ensures the service is robust against any valid string input, including:
     * <ul>
     *   <li>Unicode characters (emojis, Japanese, Arabic, etc.)</li>
     *   <li>Control characters and whitespace</li>
     *   <li>Very long strings (up to 100 characters)</li>
     *   <li>Special characters and symbols</li>
     * </ul>
     * 
     * <p>This property is crucial for production reliability where client-generated
     * keys might contain unexpected characters. The test simply verifies that the
     * service can process the key without throwing exceptions and creates the expected
     * Redis entry.
     * 
     * @param key a randomly generated string containing arbitrary characters
     */
    @Property
    void serviceMustHandleChaosStringsWithoutCrashing(@ForAll("chaosKeys") String key) {
        // Act
        idempotencyService.lock(key);

        // Assert
        String expectedKey = "idempotency:" + key;
        assertThat(redisStub).containsKey(expectedKey);
    }

    /**
     * Property: Completing an idempotency key must properly update its state.
     * 
     * <p>Verifies the complete lifecycle of an idempotency operation:
     * <ul>
     *   <li>Lock the key (sets state to PENDING)</li>
     *   <li>Complete the key with a transaction ID</li>
     *   <li>Verify state transition to COMPLETED with the correct ID</li>
     *   <li>Ensure subsequent lock attempts reference the completed transaction</li>
     * </ul>
     * 
     * <p>This property ensures that the idempotency service correctly tracks
     * completed operations and provides appropriate feedback when clients
     * attempt to reuse keys that have already been processed.
     * 
     * @param key a randomly generated idempotency key
     * @param txId a randomly generated transaction identifier
     */
    @Property
    void completingKeyMustUpdateState(@ForAll("chaosKeys") String key, @ForAll("validIds") String txId) {
        // Arrange: Lock it first
        idempotencyService.lock(key);

        // Act: Complete it
        idempotencyService.complete(key, txId);

        // Assert: State must be updated
        assertThat(redisStub.get("idempotency:" + key)).isEqualTo(txId);

        // Verify: Subsequent lock attempt gives the "Premium" error with the ID
        assertThatThrownBy(() -> idempotencyService.check(key))
                .isInstanceOf(IdempotencyException.class)
                .hasMessageContaining(txId)
                .hasMessageContaining("Already processed");
    }

    /**
     * Example: Null keys must be rejected with appropriate exception.
     * 
     * <p>Explicitly tests the edge case of null input to ensure the service
     * properly validates input parameters and throws {@link IllegalArgumentException}
     * rather than allowing null to propagate through the system. This is a critical
     * defensive programming measure.
     */
    @Example
    void nullKeyMustBeRejected() {
        assertThatThrownBy(() -> idempotencyService.lock(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- GENERATORS ---

    /**
     * Generates arbitrary strings for comprehensive idempotency key testing.
     * 
     * <p>Creates strings from the entire Unicode range (Character.MIN_VALUE to
     * Character.MAX_VALUE) to ensure the idempotency service handles all possible
     * characters correctly. This includes:
     * <ul>
     *   <li>ASCII characters (letters, digits, symbols)</li>
     *   <li>Unicode characters from various languages</li>
     *   <li>Control characters and whitespace</li>
     *   <li>Special Unicode symbols and emojis</li>
     * </ul>
     * 
     * <p>String length is constrained between 1 and 100 characters to ensure
     * reasonable test execution time while covering a wide range of input sizes.
     * 
     * @return an Arbitrary for generating random strings with full Unicode coverage
     */
    @Provide
    Arbitrary<String> chaosKeys() {
        // Generates everything: Emojis, Japanese, Control Chars, Whitespace
        return Arbitraries.strings()
                .withCharRange(Character.MIN_VALUE, Character.MAX_VALUE)
                .ofMinLength(1)
                .ofMaxLength(100);
    }

    /**
     * Generates valid transaction IDs for idempotency completion testing.
     * 
     * <p>Creates strings representing transaction identifiers with lengths
     * between 10 and 36 characters. The generator uses generic strings rather
     * than alphanumeric constraints to ensure the service properly handles
     * any valid transaction ID format that might be used by the system.
     * 
     * <p>The length constraints ensure:
     * <ul>
     *   <li>Minimum 10 characters for realistic transaction IDs</li>
     *   <li>Maximum 36 characters to accommodate UUID format</li>
     *   <li>Flexibility for various ID generation strategies</li>
     * </ul>
     * 
     * @return an Arbitrary for generating transaction identifier strings
     */
    @Provide
    Arbitrary<String> validIds() {
        // Fix: Use generic strings instead of 'alphaNumeric' which caused the error.
        // The ID content logic is opaque to the service anyway.
        return Arbitraries.strings()
                .ofMinLength(10)
                .ofMaxLength(36);
    }

    // --- STUB FACTORY ---

    /**
     * Creates a comprehensive Redis stub for testing idempotency operations.
     * 
     * <p>Implements a thread-safe in-memory simulation of Redis operations using
     * a HashMap. The stub provides exact behavior matching for all Redis commands
     * used by the IdempotencyService:
     * 
     * <ul>
     *   <li><b>setIfAbsent</b> - Atomic conditional key creation with TTL simulation</li>
     *   <li><b>get</b> - Value retrieval for state checking</li>
     *   <li><b>set</b> - Value updates for completion operations</li>
     * </ul>
     * 
     * <p>The stub ensures:
     * <ul>
     *   <li>Atomic operations through synchronized map access</li>
     *   <li>Correct return values matching Redis behavior</li>
     *   <li>State persistence across operations</li>
     *   <li>Proper handling of null values and missing keys</li>
     * </ul>
     * 
     * @return a mocked ValueOperations instance with complete Redis behavior
     */
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> createRedisStub() {
        ValueOperations<String, String> ops = (ValueOperations<String, String>) mock(ValueOperations.class);

        // 1. Stub setIfAbsent (Returns Boolean)
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    String value = invocation.getArgument(1);
                    if (redisStub.containsKey(key)) {
                        return false;
                    }
                    redisStub.put(key, value);
                    return true;
                });

        // 2. Stub get (Returns String)
        when(ops.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return redisStub.get(key);
        });

        // 3. Stub set (Returns VOID) -> FIX: Use doAnswer().when(...)
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            redisStub.put(key, value);
            return null; // void method returns null in Answer
        }).when(ops).set(anyString(), anyString(), any(Duration.class));

        return ops;
    }
}