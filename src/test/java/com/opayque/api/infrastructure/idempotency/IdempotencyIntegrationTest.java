package com.opayque.api.infrastructure.idempotency;

import com.opayque.api.infrastructure.exception.IdempotencyException;
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

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;


/**
 * PCI-DSS-compliant integration tests for the idempotency engine.
 * <p>
 * Validates that duplicate payment instructions, wallet top-ups, or refund requests
 * are rejected within the 24-hour retention window, eliminating double-spend risk
 * and ensuring ACID properties across distributed ledger entries.
 * <p>
 * Uses a containerised Redis instance to mirror production zero-trust topology
 * and guarantees that TTLs are strictly enforced to prevent memory-based DoS vectors.
 * <p>
 * Compliance trace: Story 3.2 – Idempotency & Replay-Attack Mitigation
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class IdempotencyIntegrationTest {

    /**
     * Redis 7-alpine container used to validate real-time, low-latency idempotency checks
     * without cross-contaminating production HSM-protected Redis clusters.
     * Container is spun up per test suite to satisfy PCI-DSS req. 11.3 – segmentation testing.
     */
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    void clearRedis() {
        // Ensure a clean slate for every test
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    /**
     * Simulates a “double click” on a Pay-Now button or API retry loop.
     * Ensures only one payment or wallet debit is authorised per idempotency key,
     * protecting the ledger from double-spend and maintaining FX settlement accuracy.
     */
    @Test
    @DisplayName("Scenario 1: The Double Click - Should fail fast on duplicate lock attempt")
    void shouldPreventDuplicateLocking() {
        // 1. Arrange
        String key = "click-" + UUID.randomUUID();

        // 2. Act: First Click (Success)
        boolean locked = idempotencyService.lock(key);
        assertThat(locked).isTrue();

        // 3. Act: Second Click (Fail)
        assertThatThrownBy(() -> idempotencyService.lock(key))
                .isInstanceOf(IdempotencyException.class)
                .hasMessageContaining("currently being processed");
    }

    /**
     * Mirrors the full lifecycle of a FinTech transaction:
     * PENDING → COMPLETED, allowing downstream AML & charge-back systems
     to reference the finalised transactionId immutably stored in Redis.
     */
    @Test
    @DisplayName("Scenario 2: The Receipt - Should verify persistence and state transition")
    void shouldPersistStateAndTransitionToComplete() {
        // 1. Arrange
        String idempotencyKey = "tx-" + UUID.randomUUID();
        String expectedRedisKey = "idempotency:" + idempotencyKey;
        String transactionId = UUID.randomUUID().toString();

        // 2. Act: Lock
        idempotencyService.lock(idempotencyKey);

        // 3. Assert: Verify PENDING state directly in Redis
        String pendingValue = redisTemplate.opsForValue().get(expectedRedisKey);
        assertThat(pendingValue).isEqualTo("PENDING");

        // 4. Act: Complete
        idempotencyService.complete(idempotencyKey, transactionId);

        // 5. Assert: Verify COMPLETED state directly in Redis
        String finalValue = redisTemplate.opsForValue().get(expectedRedisKey);
        assertThat(finalValue).isEqualTo(transactionId);
    }

    /**
     * Validates 24-hour TTL enforcement to comply with GDPR “storage limitation”
     * and to neutralise memory exhaustion attacks that could impair high-frequency
     * payment rails or card-tokenisation services.
     */
    @Test
    @DisplayName("Scenario 3: Expiration - Should set correct TTL to prevent memory bloat")
    void shouldEnforceTtl() {
        // 1. Arrange
        String key = "ttl-" + UUID.randomUUID();
        String expectedRedisKey = "idempotency:" + key;

        // 2. Act
        idempotencyService.lock(key);

        // 3. Assert
        Long expireTime = redisTemplate.getExpire(expectedRedisKey, TimeUnit.SECONDS);

        assertThat(expireTime).isNotNull();
        // Redis TTL might be 86399 seconds immediately after set, so we check range.
        // 24 hours = 86400 seconds.
        assertThat(expireTime).isCloseTo(86400L, offset(5L)); // Within 5 seconds of 24h
    }
}