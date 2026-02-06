package com.opayque.api.infrastructure.idempotency;

import com.opayque.api.infrastructure.exception.IdempotencyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/// Explicit Idempotency Orchestrator.
///
/// Ensures that critical financial operations are executed exactly once,
/// regardless of network retries or client double-clicks.
///
/// **Architecture:**
/// Uses Redis `SETNX` (Set if Not Exists) to acquire a distributed lock on the
/// client-provided `Idempotency-Key`.
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String KEY_PREFIX = "idempotency:";
    private static final Duration TTL = Duration.ofHours(24);

    /// Attempts to acquire a lock for the given idempotency key.
    ///
    /// **Logic:**
    /// 1. Tries to set the key to "PENDING" if it doesn't exist.
    /// 2. If successful, returns true (Access Granted).
    /// 3. If failed (Key exists), fetches the existing value to see if it was
    ///    a previous success (Transaction ID) or a concurrent race ("PENDING").
    ///
    /// @param key The unique key provided by the client (e.g., UUID).
    /// @return true if the lock was acquired.
    /// @throws IdempotencyException If the key is already locked or processed.
    public boolean lock(String key) {
        if (key == null) {
            throw new IllegalArgumentException("Idempotency key cannot be null");
        }

        String redisKey = KEY_PREFIX + key;

        // 1. The Atomic "Check-Then-Set"
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, "PENDING", TTL);

        if (Boolean.TRUE.equals(acquired)) {
            log.debug("Idempotency Lock Acquired: [{}]", key);
            return true;
        }

        // 2. Collision Detected - Enrich the Error
        String existingValue = redisTemplate.opsForValue().get(redisKey);
        String message;

        if ("PENDING".equals(existingValue)) {
            message = String.format("Duplicate request. Request [%s] is currently being processed.", key);
        } else {
            // It contains the Transaction ID from complete()
            message = String.format("Duplicate request. Transaction [%s] already processed for key [%s].", existingValue, key);
        }

        log.warn("Idempotency Collision: {}", message);
        throw new IdempotencyException(message);
    }

    /// Marks the idempotency key as "COMPLETED" by storing the resulting Transaction ID.
    ///
    /// This allows future duplicate requests to be informed specifically *which*
    /// transaction satisfied their request, enabling them to look it up.
    ///
    /// @param key The original idempotency key.
    /// @param transactionId The ID of the successfully committed transaction.
    public void complete(String key, String transactionId) {
        String redisKey = KEY_PREFIX + key;
        redisTemplate.opsForValue().set(redisKey, transactionId, TTL);
        log.debug("Idempotency Completed: Key=[{}] -> Tx=[{}]", key, transactionId);
    }
}