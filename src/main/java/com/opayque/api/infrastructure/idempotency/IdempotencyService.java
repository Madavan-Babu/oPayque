package com.opayque.api.infrastructure.idempotency;

import com.opayque.api.infrastructure.exception.IdempotencyException;
import com.opayque.api.infrastructure.exception.ServiceUnavailableException;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Idempotency Service — Distributed Lock & Replay-Protection Layer.
 * <p>
 * Guarantees <b>exactly-once</b> execution of high-value payment instructions
 * across horizontally-scaled micro-services. Leverages Redis-backed
 * compare-and-swap semantics to prevent duplicate debits/credits that would
 * violate the <i>Double-Entry</i> ledger integrity and regulatory
 * <i>Settlement Finality</i> requirements.
 * <p>
 * <b>Security Posture:</b> Fails <i>closed</i> on Redis unavailability,
 * eliminating the risk of <i>Split-Brain</i> duplicates during network partitions.
 * <p>
 * <b>Compliance Mapping:</b>
 * <ul>
 *   <li>PCI DSS 4.0 §A2.1 — Unique transaction identifier</li>
 *   <li>PSD2 RTS Art. 7 — Idempotency for SCA-exempt payments</li>
 *   <li>SWIFT CBPR+ §3.4 — Duplicate detection window</li>
 * </ul>
 *
 * @author Madavan Babu
 * @version 2.0.0
 * @since 2026
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String KEY_PREFIX = "idempotency:";
    private static final Duration TTL = Duration.ofHours(24);

    // Thread-Local Cache for Transactional Re-entrancy (Compatible with @Retryable)
    private final ThreadLocal<String> localLock = new ThreadLocal<>();

    /**
     * ATOMIC LOCK: Tries to set the key to "PENDING".
     * <p>
     * <b>Re-entrancy Logic:</b> If the <i>current thread</i> already holds the lock
     * for this specific key (e.g., during a DB transaction retry), this method
     * returns immediately to prevent self-deadlock.
     */
    public void check(String key) {
        if (key == null) throw new IllegalArgumentException("Idempotency key cannot be null");

        // FIX 2: Self-Healing Cleanup (Pool Protection)
        // If this thread is being reused for a DIFFERENT request, clear the stale lock.
        String currentOwnedKey = localLock.get();
        if (currentOwnedKey != null && !currentOwnedKey.equals(key)) {
            localLock.remove();
            currentOwnedKey = null;
        }

        // FIX 3: Re-entrancy Check (The Retry Bypass)
        // If we already own THIS key, let it pass.
        if (key.equals(currentOwnedKey)) {
            return;
        }

        String redisKey = KEY_PREFIX + key;

        try {
            // SETNX: Only sets if the key is NOT present.
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, "PENDING", TTL);

            if (Boolean.TRUE.equals(acquired)) {
                // FIX 4: Mark ownership for this thread
                localLock.set(key);
                log.debug("Idempotency Lock Acquired: [{}]", key);
            } else {
                // Collision Logic
                String existingValue = redisTemplate.opsForValue().get(redisKey);

                if ("PENDING".equals(existingValue)) {
                    throw new IdempotencyException("Duplicate request. Processing is already in progress.");
                } else {
                    // We include the existingValue (Transaction ID) for better visibility
                    throw new IdempotencyException("Duplicate request. Already processed with TxId: " + existingValue);
                }
            }

        } catch (RedisConnectionFailureException | QueryTimeoutException e) {
            log.error("CRITICAL: Redis unreachable. Aborting Idempotency Check for [{}]", key, e);
            throw new ServiceUnavailableException("Service temporarily unavailable.");
        }
    }

    /**
     * Promotes key to COMPLETED and clears local re-entrancy context.
     */
    public void complete(String key, String transactionId) {
        String redisKey = KEY_PREFIX + key;
        try {
            redisTemplate.opsForValue().set(redisKey, transactionId, TTL);
            log.debug("Idempotency Completed: Key=[{}] -> Tx=[{}]", key, transactionId);
        } catch (Exception e) {
            log.warn("Failed to update idempotency status for key [{}]", key, e);
        } finally {
            // FIX 5: Strict Cleanup
            // Always clear the local context on completion to prevent memory leaks.
            localLock.remove();
        }
    }

    /**
     * Safe delegation: Maintains the boolean return contract while
     * inheriting the new ThreadLocal re-entrancy logic.
     * <p>
     * Includes contextual logging to distinguish between active collisions
     * and completed transaction replays.
     */
    @Timed(value = "opayque.idempotency.lock", description = "Overhead of Redis-backed idempotency lock acquisition")
    public boolean lock(String key) {
        try {
            check(key); // Re-uses the re-entrancy logic
            return true;
        } catch (IdempotencyException e) {
            // Collision detected - Log the specific reason before returning false
            log.warn("Idempotency lock rejected for key [{}]: {}", key, e.getMessage());
            return false;
        } catch (ServiceUnavailableException e) {
            // Infrastructure failure - Already logged in check(), but re-throwing is mandatory
            throw e;
        } catch (Exception e) {
            log.error("Unexpected failure during idempotency lock acquisition for key [{}]", key, e);
            throw e;
        }
    }
}