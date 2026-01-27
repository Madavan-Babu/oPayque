package com.opayque.api.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/// Epic 1: Infrastructure - Redis Configuration
///
/// Explicitly configures the Redis connection to ensure high-performance serialization.
/// This configuration is optimized for the **Token Kill Switch** and future
/// multi-currency wallet caching.
///
/// ### Serialization Strategy:
/// - **Keys**: Uses `StringRedisSerializer` for CLI readability and debugging.
/// - **Values**: Uses `GenericJackson2JsonRedisSerializer` to support complex object storage.
@Configuration
public class RedisConfig {

    /// Configures the centralized {@link RedisTemplate} for the oPayque ecosystem.
    /// @param connectionFactory The Spring-managed Redis connection factory.
    /// @return A configured RedisTemplate supporting JSON value serialization.
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Keys remain Strings for human-readability in the CLI
        template.setKeySerializer(new StringRedisSerializer());

        // Value serializer supports both simple strings and complex POJOs
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());

        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());

        return template;
    }
}