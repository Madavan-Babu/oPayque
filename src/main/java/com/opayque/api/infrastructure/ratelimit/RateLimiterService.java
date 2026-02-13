package com.opayque.api.infrastructure.ratelimit;

import com.opayque.api.infrastructure.exception.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;



/**
 * PCI-DSS-compliant distributed rate-limiter that enforces per-user velocity limits
 * across horizontally-scoped payment contexts.
 * <p>
 * Supports two primary throttling dimensions:
 * <ul>
 *   <li>Transfers – defaults to 10 operations/minute to mitigate fraudulent high-velocity
 *       outbound flows and protect Nostro liquidity.</li>
 *   <li>Card Issuance – capped at 3 operations/minute to prevent card-farming attacks
 *       and synthetic identity abuse during KYC-light onboarding.</li>
 * </ul>
 * <p>
 * Relies on Redis atomic INCR + EXPIRE for sub-millisecond adjudication without
 * introducing single-point-of-failure statefulness into the API gateway layer.
 * Fail-open semantics ensure service continuity when Redis is unreachable,
 * aligning with Basel III operational-resilience guidance.
 * <p>
 * Compliance trace: Story 3.3 & 4.3 – Distributed Rate-Limiter Strategy
 *
 * @author Madavan Babu
 * @since 2026
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimiterService {

    private final RedisTemplate<String, String> redisTemplate;
    private static final Duration WINDOW_SIZE = Duration.ofMinutes(1);

    // Default Limits
    private static final long LIMIT_TRANSFERS = 10; // 10 tx/min
    private static final long LIMIT_CARD_ISSUE = 3;  // 3 cards/min (Prevents Farming)

    /**
     * Legacy entry-point that enforces the default transfer velocity limit
     * (10 tx/min) for the supplied user.
     * <p>
     * Delegates to {@link #checkLimit(String, String, long)} with action
     * "transfers" and limit {@value #LIMIT_TRANSFERS}.
     *
     * @param userId the immutable user identifier (UUID v4 preferred).
     * @throws RateLimitExceededException if the user has exceeded the
     *         transfer throttle threshold within the current 60-second window.
     */
    public void checkLimit(String userId) {
        checkLimit(userId, "transfers", LIMIT_TRANSFERS);
    }

    /**
     * Generic throttling engine that atomically increments a Redis-backed counter
     * scoped to the tuple (action, userId) and enforces the specified limit
     * within a rolling 60-second window.
     * <p>
     * Implements fail-open behaviour: if Redis connectivity is lost the
     * method returns immediately, allowing traffic to proceed and preventing
     * cascade failures across the payment rail.
     * <p>
     * Security events are emitted at WARN level for SIEM ingestion, capturing
     * userId, action, and current count to facilitate post-event forensic
     * analysis and AML transaction profiling.
     *
     * @param userId the immutable user identifier (UUID v4 preferred).
     * @param action the action context, e.g., "transfers" or "card_issue".
     * @param limit  the maximum allowable requests per window for this context.
     * @throws RateLimitExceededException when the counter exceeds the limit
     *         within the current window.
     */
    public void checkLimit(String userId, String action, long limit) {
        String key = "rate:" + action + ":" + userId;

        // 1. Atomic Increment
        Long currentCount = redisTemplate.opsForValue().increment(key);

        // Safety check for Redis failure
        if (currentCount == null) {
            log.error("Rate Limiter failed to connect to Redis. Allowing traffic to fail-open.");
            return;
        }

        // 2. Window Initialization
        if (currentCount == 1) {
            redisTemplate.expire(key, WINDOW_SIZE);
        }

        // 3. The Enforcer
        if (currentCount > limit) {
            log.warn("SECURITY_EVENT: Rate_Limit_Exceeded | User: [{}] | Action: [{}] | Count: [{}]", userId, action, currentCount);
            throw new RateLimitExceededException("Rate limit exceeded for " + action + ". Try again later.");
        }
    }
}