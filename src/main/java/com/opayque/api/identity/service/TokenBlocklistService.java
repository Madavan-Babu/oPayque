package com.opayque.api.identity.service;

import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/// Epic 1: Identity - Token Kill Switch
///
/// Service responsible for managing the lifecycle of revoked JWT signatures.
/// Utilizes Redis with TTL (Time-To-Live) to automatically purge signatures once
/// the original tokens naturally expire.
///
/// ### Design Strategy:
/// - **Opaque Revocation**: Blocks only the HMAC signature portion of the JWT to minimize memory footprint.
/// - **Auto-Purge**: Relies on Redis expiration logic to maintain a lean blocklist.
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenBlocklistService {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final String KEY_PREFIX = "blacklist:";

    /// Adds a JWT signature to the Redis Blocklist.
    ///
    /// This "Kill Switch" ensures that logged-out or compromised tokens cannot be reused.
    ///
    /// @param signature The unique signature (HMAC) segment of the token to block.
    /// @param ttl The remaining lifespan of the token before it would naturally expire.
    public void blockToken(String signature, Duration ttl) {
        // Edge Case Handling: Prevent blocking already expired tokens.
        if (ttl.isNegative() || ttl.isZero()) {
            log.warn("Attempted to block an already expired token. Signature fragment: {}",
                    signature.substring(0, Math.min(signature.length(), 8)));
            return;
        }

        String key = KEY_PREFIX + signature;

        // Persist to Redis and set the TTL for automatic eviction.
        redisTemplate.opsForValue().set(key, "blocked", ttl);

        log.info("Token blocked successfully. TTL: {} seconds", ttl.getSeconds());
    }

    /// Checks if a token signature is currently present in the blocklist.
    ///
    /// @param signature The JWT signature to verify.
    /// @return true if the signature is present (blocked), false if valid.
    @Timed(value = "opayque.auth.blocklist.check", description = "Performance of Redis lookups for revoked tokens")
    public boolean isBlocked(String signature) {
        String key = KEY_PREFIX + signature;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}