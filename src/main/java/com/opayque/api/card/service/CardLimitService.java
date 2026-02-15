package com.opayque.api.card.service;

import com.opayque.api.infrastructure.exception.CardLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Service responsible for enforcing card velocity and spending limits using a
 * distributed Redis accumulator.
 * <p>
 * This implementation uses Atomic Lua Scripting to eliminate TOCTOU race conditions
 * during high-concurrency limit checks (The "Photo Finish" fix).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CardLimitService {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final long KEY_TTL_DAYS = 32;

    // LUA SCRIPT: Atomic Get -> Add -> Compare -> Increment + Expire
    // This script runs as a single atomic unit within Redis.
    // LUA SCRIPT: Returns 1 (Success) or -1 (Breach)
    // Using Long avoids JSON Double-deserialization hell.
    private static final String LUA_LIMIT_CHECK =
            "local current = tonumber(redis.call('get', KEYS[1]) or '0') " +
                    "local amount = tonumber(ARGV[1]) " +
                    "local limit = tonumber(ARGV[2]) " +
                    "if current + amount > limit then " +
                    "  return -1 " +
                    "else " +
                    "  redis.call('incrbyfloat', KEYS[1], amount) " +
                    "  redis.call('expire', KEYS[1], ARGV[3]) " +
                    "  return 1 " +
                    "end";

    /**
     * Atomically checks and reserves the spending limit in Redis.
     * Prevents race conditions where multiple threads see the same "old" balance.
     *
     * @param cardId       The UUID of the Virtual Card.
     * @param monthlyLimit The strict limit defined on the card (e.g., 1000.00).
     * @param amount       The amount trying to be spent.
     * @throws CardLimitExceededException if the limit is breached.
     */
    @Transactional(readOnly = true)
    public void checkSpendLimit(UUID cardId, BigDecimal monthlyLimit, BigDecimal amount) {
        // FIX: Add this null check at the very top.
        // If a card has no limit, we should approve immediately.
        if (monthlyLimit == null) {
            return;
        }

        String key = generateKey(cardId);
        long ttlSeconds = TimeUnit.DAYS.toSeconds(KEY_TTL_DAYS);

        // FIX: Use Long.class as the return type for stable serialization
        RedisScript<Long> script = new DefaultRedisScript<>(LUA_LIMIT_CHECK, Long.class);

        // FIX: Pass all arguments as Strings. Redis tonumber() handles them perfectly,
        // bypassing Jackson serialization issues.
        // FIX: Use GenericToStringSerializer to bypass the Jackson JSON Serializer.
        // This ensures raw Redis integers are correctly mapped to Java Longs.
        Long result = redisTemplate.execute(
                script,
                redisTemplate.getStringSerializer(), // Use String for Keys
                new GenericToStringSerializer<>(Long.class), // Use String-to-Long for Result
                Collections.singletonList(key),
                amount.toString(),
                monthlyLimit.toString(),
                String.valueOf(ttlSeconds)
        );

        // FIX: Compare against -1L (Long literal)
        if (result == null || result == -1L) {
            log.warn("Card Limit Breached | Card: {} | Limit: {}", cardId, monthlyLimit);
            throw new CardLimitExceededException("Monthly spending limit exceeded. Limit: " + monthlyLimit);
        }

        log.debug("Limit Reserved in Redis | Card: {} | New Total: {}", cardId, result);
    }

    /**
     * Updates the Redis accumulator.
     * <p>
     * NOTE: This logic is now handled ATOMICALLY in checkSpendLimit.
     * This method is kept as a no-op for API compatibility to avoid breaking existing
     * Service/Test wiring, but it no longer performs the increment to prevent double-counting.
     */
    @Transactional
    public void recordSpend(UUID cardId, BigDecimal amount) {
        log.debug("recordSpend bypassed. Atomic increment already completed in checkSpendLimit for Card: {}", cardId);
    }

    /**
     * COMPENSATING TRANSACTION: Reverts a spend reservation in Redis.
     * Essential for Chaos/Consistency tests where a Database write fails after a Redis check.
     *
     * @param cardId The UUID of the Virtual Card.
     * @param amount The amount to return to the available limit.
     */
    @Transactional
    public void rollbackSpend(UUID cardId, BigDecimal amount) {
        String key = generateKey(cardId);
        redisTemplate.opsForValue().increment(key, amount.negate().doubleValue());
        log.info("Rollback: Limit returned to Redis | Card: {} | Amount: {}", cardId, amount);
    }

    private String generateKey(UUID cardId) {
        // Pattern: spend:{card_id}:{YYYY-MM} -> Resets automatically every month
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        return "spend:" + cardId + ":" + month;
    }
}