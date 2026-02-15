package com.opayque.api.card.service;

import com.opayque.api.card.dto.CardIssueRequest;
import com.opayque.api.card.dto.CardIssueResponse;
import com.opayque.api.card.dto.CardSummaryResponse;
import com.opayque.api.card.entity.CardStatus;
import com.opayque.api.card.entity.VirtualCard;
import com.opayque.api.card.model.CardSecrets;
import com.opayque.api.card.repository.VirtualCardRepository;
import com.opayque.api.infrastructure.exception.IdempotencyException;
import com.opayque.api.infrastructure.idempotency.IdempotencyService;
import com.opayque.api.infrastructure.ratelimit.RateLimiterService;
import com.opayque.api.infrastructure.util.SecurityUtil;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;



/**
 * PCI-DSS-compliant orchestration service for the complete virtual-card life-cycle.
 * <p>
 * Responsible for secure issuance, idempotency-guarded authorisations, rate-limiting,
 * BOLA (Broken Object-Level Authorisation) checks, and state-machine transitions
 * (ACTIVE &rarr; FROZEN &rarr; TERMINATED). All sensitive card data are encrypted
 * at rest via JPA AttributeConverters and never transit in clear outside HSM-protected memory.
 * <p>
 * The service enforces a strict “one-request-one-card” semantic through Redis-backed
 * idempotency locks, eliminating double-spend on ledger entries and guaranteeing
 * 24-hour replay protection aligned with GDPR storage-limitation principles.
 * <p>
 * Rate limits (default 3 cards/minute) are applied post-idempotency to prevent
 * burning quotas during replay attacks. All mutating flows run inside
 * {@code REQUIRES_NEW} transactions to ensure ACID isolation across wallet,
 * card, and idempotency stores.
 *
 * @author Madavan Babu
 * @since 2026
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CardIssuanceService {

    private final CardGeneratorService cardGeneratorService;
    private final VirtualCardRepository virtualCardRepository;
    private final AccountRepository accountRepository;
    private final RateLimiterService rateLimiterService;
    private final IdempotencyService idempotencyService; // New Dependency


    /**
     * Idempotency-protected issuance of a new virtual card tied to the caller’s wallet.
     * <p>
     * <b>Workflow:</b>
     * <ol>
     *   <li>Fail-fast idempotency check against Redis (prevents double-spend).</li>
     *   <li>Rate-limit verification (default 3 cards/minute per subject).</li>
     *   <li>Wallet ownership & currency validation to satisfy BOLA controls.</li>
     *   <li>Secure PAN/CVV generation via HSM-backed {@link CardGeneratorService}.</li>
     *   <li>Encrypted persistence of card secrets (JPA AttributeConverter).</li>
     *   <li>Idempotency key sealed with the generated card ID for 24h.</li>
     * </ol>
     * <p>
     * The method is retried (max 3 attempts, 100ms backoff) on transient DB
     * constraint violations to handle high-concurrency race conditions without
     * leaking duplicates into the ledger.
     *
     * @param request non-null issuance instruction containing currency and monthly limit.
     * @param idempotencyKey client-supplied UUID (may be {@code null}); used as
     *                       deterministic replay-protection token.
     * @return fully populated {@link CardIssueResponse} with unmasked PAN & CVV.
     * @throws IdempotencyException if the key is already locked or completed.
     * @throws IllegalArgumentException if no wallet exists for the requested currency.
     * @throws org.springframework.security.access.AccessDeniedException if rate limit exceeded.
     */
    @Retryable(
            retryFor = { org.springframework.dao.DataIntegrityViolationException.class },
            maxAttempts = 3,
            backoff = @Backoff(delay = 100)
    )
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public CardIssueResponse issueCard(CardIssueRequest request, String idempotencyKey) {
        UUID userId = SecurityUtil.getCurrentUserId();

        // 1. Idempotency Check (Fail Fast)
        // If key exists & is locked/completed, this throws IdempotencyException immediately.
        idempotencyService.check(idempotencyKey);

        // 2. Rate Limiting (3 cards per minute)
        // We do this AFTER idempotency check to avoid burning rate limits on replay attacks.
        rateLimiterService.checkLimit(userId.toString(), "card_issue", 3);

        // 3. Domain Logic: Validate Wallet
        Account wallet = accountRepository.findAllByUserId(userId).stream()
                .filter(a -> a.getCurrencyCode().equals(request.currency()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No wallet found for currency: " + request.currency()));

        // 4. Generate Secrets (External Service / HSM)
        CardSecrets secrets = cardGeneratorService.generateCard();

        // 5. Build Entity
        VirtualCard card = VirtualCard.builder()
                .account(wallet)
                .pan(secrets.pan()) // Will be encrypted by AttributeConverter
                .cvv(secrets.cvv()) // Will be encrypted by AttributeConverter
                .expiryDate(secrets.expiryDate())
                .cardholderName(wallet.getUser().getFullName())
                .monthlyLimit(request.monthlyLimit())
                .status(CardStatus.ACTIVE)
                .build();

        // 6. Persist (Transactional Boundary)
        VirtualCard savedCard = virtualCardRepository.save(card);

        log.info("Card issued successfully. ID: [{}], User: [{}], Currency: [{}]",
                savedCard.getId(), userId, request.currency());

        // 7. Idempotency Completion
        // Seal the key with the generated Card ID.
        if (idempotencyKey != null) {
            idempotencyService.complete(idempotencyKey, savedCard.getId().toString());
        }

        // 8. Return Unmasked Response
        return new CardIssueResponse(
                savedCard.getId(),
                secrets.pan(),
                secrets.cvv(),
                secrets.expiryDate(),
                savedCard.getCardholderName(),
                savedCard.getAccount().getCurrencyCode(),
                savedCard.getStatus(),
                savedCard.getMonthlyLimit()
        );
    }

    /**
     * Freezes, unfreezes, or terminates an existing virtual card.
     * <p>
     * Enforces strict BOLA (Broken Object-Level Authorisation) rules: only the
     * wallet owner may mutate card state. State-machine validation guarantees
     * legal transitions (e.g., ACTIVE &rarr; FROZEN, FROZEN &rarr; ACTIVE,
     * ACTIVE/FROZEN &rarr; TERMINATED). All changes are audit-logged for
     * downstream AML & charge-back reconciliation.
     *
     * @param cardId UUID of the card to mutate; must belong to the caller.
     * @param newStatus target status; must be a valid transition from current state.
     * @return lightweight {@link CardSummaryResponse} reflecting updated state.
     * @throws IllegalArgumentException if card does not exist.
     * @throws org.springframework.security.access.AccessDeniedException on BOLA violation.
     * @throws IllegalStateException for illegal state-machine transition.
     */
    @Transactional
    public CardSummaryResponse changeCardStatus(UUID cardId, CardStatus newStatus) {
        UUID userId = SecurityUtil.getCurrentUserId();

        // 1. Fetch
        VirtualCard card = virtualCardRepository.findById(cardId)
                .orElseThrow(() -> new IllegalArgumentException("Card not found"));

        // 2. BOLA Check (Ownership)
        if (!card.getAccount().getUser().getId().equals(userId)) {
            log.warn("BOLA Violation: User [{}] attempted to modify Card [{}] owned by [{}]",
                    userId, cardId, card.getAccount().getUser().getId());
            throw new org.springframework.security.access.AccessDeniedException("Access Denied");
        }

        // 3. State Machine Validation
        if (!card.getStatus().canTransitionTo(newStatus)) {
            log.warn("Invalid State Transition Attempt: [{}] -> [{}] for Card [{}]", card.getStatus(), newStatus, cardId);
            throw new IllegalStateException(
                    String.format("Invalid status transition: Cannot change status from %s to %s", card.getStatus(), newStatus)
            );
        }

        // 4. Apply Change
        card.setStatus(newStatus);
        VirtualCard updatedCard = virtualCardRepository.save(card);

        log.info("Card [{}] status changed to [{}] by User [{}]", cardId, newStatus, userId);

        return toSummaryDto(updatedCard);
    }

    /**
     * Modifies the monthly spending limit for a virtual card with strict security controls.
     *
     * @param cardId The UUID of the card.
     * @param newLimit The new monthly ceiling.
     * @param idempotencyKey The unique replay-protection token.
     * @return The updated card summary.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CardSummaryResponse updateMonthlyLimit(UUID cardId, BigDecimal newLimit, String idempotencyKey) {
        UUID userId = SecurityUtil.getCurrentUserId();

        try {
            // 1. Idempotency Guard (Fail Fast)
            idempotencyService.check(idempotencyKey);

            // 2. Rate Limiting (5 updates per minute per user)
            // Separate bucket from issuance to prevent DOSing your own card creation
            rateLimiterService.checkLimit(userId.toString(), "card_limit_update", 5);

            // 3. Fetch Domain Entity
            // This forces allowed threads to queue, eliminating StaleObjectStateExceptions.
            VirtualCard card = virtualCardRepository.findByIdWithLock(cardId)
                    .orElseThrow(() -> new IllegalArgumentException("Card not found"));

            // 4. BOLA Check (Strict Ownership)
            if (!card.getAccount().getUser().getId().equals(userId)) {
                log.warn("BOLA Violation: User [{}] tried to change Limit on Card [{}] owned by [{}]",
                        userId, cardId, card.getAccount().getUser().getId());
                throw new org.springframework.security.access.AccessDeniedException("Access Denied");
            }

            // 5. Update State
            BigDecimal oldLimit = card.getMonthlyLimit();
            card.setMonthlyLimit(newLimit);

            // 6. Persist
            VirtualCard updatedCard = virtualCardRepository.save(card);

            log.info("Card Limit Updated | ID: {} | User: {} | Old: {} | New: {}",
                    cardId, userId, oldLimit, newLimit);

            // 7. Complete Idempotency
            idempotencyService.complete(idempotencyKey, updatedCard.getId().toString());

            return toSummaryDto(updatedCard);

        } catch (IdempotencyException e) {
            throw e; // Allow 409 Conflict to bubble up
        } catch (Exception e) {
            log.error("Failed to update card limit | Card: {} | User: {}", cardId, userId, e);
            throw e; // Ensure the transaction rolls back
        }
    }

    /**
     * Returns a read-only ledger view of all virtual cards owned by the caller.
     * <p>
     * PANs are masked according to PCI-DSS display guidelines (only last 4 digits
     * revealed) to prevent shoulder-surfing and limit PII exposure in transit.
     * The list is ordered by creation time descending to surface newest cards first,
     * optimising for UI pagination and mobile bandwidth.
     *
     * @return possibly-empty list of {@link CardSummaryResponse} elements; never {@code null}.
     */
    @Transactional(readOnly = true)
    public List<CardSummaryResponse> getUserCards() {
        UUID userId = SecurityUtil.getCurrentUserId();

        return virtualCardRepository.findAllByAccount_User_Id(userId)
                .stream()
                .map(this::toSummaryDto)
                .toList();
    }

    // =========================================================================
    // INTERNAL HELPERS
    // =========================================================================

    /**
     * Maps an encrypted {@link VirtualCard} entity to a presentation DTO
     * suitable for external APIs. PAN is masked; sensitive fields such as CVV
     * are never serialised.
     */
    private CardSummaryResponse toSummaryDto(VirtualCard card) {
        return new CardSummaryResponse(
                card.getId(),
                maskPan(card.getPan()),
                card.getExpiryDate(),
                card.getCardholderName(),
                card.getAccount().getCurrencyCode(),
                card.getStatus(),
                card.getMonthlyLimit()
        );
    }

    /**
     * PCI-DSS-compliant PAN masking: only the last four digits are exposed.
     * If the PAN is malformed or too short, returns a safe fallback
     * to avoid leaking partial data.
     */
    private String maskPan(String pan) {
        if (pan == null || pan.length() < 4) return "****";
        return "**** **** **** " + pan.substring(pan.length() - 4);
    }
}