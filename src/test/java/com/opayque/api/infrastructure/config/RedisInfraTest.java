package com.opayque.api.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// Epic 1: Infrastructure - Redis Connectivity Testing
///
/// This integration test validates the primary caching bridge for the oPayque API.
/// It ensures the application can successfully communicate with the Redis nodes
/// required for token revocation and future wallet caching.
@SpringBootTest
class RedisInfraTest {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /// Verifies the full read-write-delete cycle of the Redis infrastructure.
    ///
    /// Utilizes UUIDs to prevent key collisions during concurrent CI/CD execution
    /// in the cloud environment.
    @Test
    @DisplayName("Infra: Should be able to Write and Read from Redis")
    void shouldConnectToRedis() {
        // Arrange: Generate a unique infrastructure heartbeat key
        String key = "test:connection:" + UUID.randomUUID();
        String value = "connected";

        // Act: Perform a stateless round-trip
        redisTemplate.opsForValue().set(key, value);
        Object retrievedValue = redisTemplate.opsForValue().get(key);

        // Assert: Validate data integrity
        assertThat(retrievedValue).isEqualTo(value);

        // Cleanup: Maintain a clean ledger state
        redisTemplate.delete(key);
    }
}