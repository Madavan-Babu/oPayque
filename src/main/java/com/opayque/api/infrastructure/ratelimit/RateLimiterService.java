package com.opayque.api.infrastructure.ratelimit;

import com.opayque.api.infrastructure.exception.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/// **Story 3.3: Distributed Rate Limiter**.
///
/// Implements the "Fixed Window Counter" algorithm using Redis.
///
/// **Why Redis?**
/// - **Atomic:** `INCR` operations are atomic, preventing race conditions where
///   two requests read "9" and both write "10".
/// - **Distributed:** Works across multiple API instances (Kubernetes ready).
/// - **Ephemeral:** Uses TTL to auto-reset windows without background cleanup threads.
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimiterService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final long MAX_REQUESTS_PER_MINUTE = 10;
    private static final Duration WINDOW_SIZE = Duration.ofMinutes(1);
    private static final String PREFIX = "rate:transfers:";

    /// Enforces the strict traffic policy.
    ///
    /// @param userId The unique identifier of the user (e.g., email or UUID).
    /// @throws RateLimitExceededException if the user has sent > 10 requests in the last minute.
    public void checkLimit(String userId) {
        String key = PREFIX + userId;

        // 1. Atomic Increment (The "Ticket" Mechanism)
        // Returns the NEW value after incrementing. If key doesn't exist, it creates it at 0 then increments to 1.
        Long currentCount = redisTemplate.opsForValue().increment(key);

        // Safety check for null (rare Redis connection edge case)
        if (currentCount == null) {
            log.error("Rate Limiter failed to connect to Redis. Allowing traffic to fail-open.");
            return;
        }

        // 2. Window Initialization (The "Timer" Mechanism)
        // If this is the FIRST request in the new window (count == 1), set the TTL.
        if (currentCount == 1) {
            redisTemplate.expire(key, WINDOW_SIZE);
            log.debug("Rate Limit window started for user: {}", userId);
        }

        // 3. The Enforcer (The "Bouncer" Mechanism)
        if (currentCount > MAX_REQUESTS_PER_MINUTE) {
            log.warn("SECURITY_EVENT: Rate_Limit_Exceeded | User: [{}] | Count: [{}] | Action: BLOCKED", userId, currentCount);
            throw new RateLimitExceededException("Rate limit exceeded. Try again later.");
        }
    }
}