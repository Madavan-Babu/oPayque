package com.opayque.api.card.service;

import com.opayque.api.card.dto.CardTransactionRequest;
import com.opayque.api.card.dto.CardTransactionResponse;
import com.opayque.api.card.entity.CardStatus;
import com.opayque.api.card.entity.PaymentStatus;
import com.opayque.api.card.entity.VirtualCard;
import com.opayque.api.card.repository.VirtualCardRepository;
import com.opayque.api.infrastructure.encryption.AttributeEncryptor;
import com.opayque.api.infrastructure.exception.IdempotencyException;
import com.opayque.api.infrastructure.idempotency.IdempotencyService;
import com.opayque.api.infrastructure.ratelimit.RateLimiterService;
import com.opayque.api.wallet.dto.CreateLedgerEntryRequest;
import com.opayque.api.wallet.entity.TransactionType;
import com.opayque.api.wallet.service.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Orchestrator service for simulating external card network transactions.
 * <p>
 * Flows: Idempotency -> Lookup -> Auth -> Risk -> Ledger -> Response.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CardTransactionService {

    private final VirtualCardRepository virtualCardRepository;
    private final CardLimitService cardLimitService;
    private final LedgerService ledgerService;
    private final AttributeEncryptor attributeEncryptor;
    private final RateLimiterService rateLimiterService;
    private final IdempotencyService idempotencyService;

    // Rate Limit: 20 Transactions per minute per card (prevents brute force)
    private static final long RATE_LIMIT_QUOTA = 20;

    /**
     * Processes a simulated card swipe (POS Transaction).
     *
     * @param request The ISO-8583 style request payload.
     * @return The transaction result.
     */
    @Transactional(noRollbackFor = {BadCredentialsException.class, AccessDeniedException.class})
    public CardTransactionResponse processTransaction(CardTransactionRequest request) {
        String idempotencyKey = "txn:" + request.getExternalTransactionId();

        // FIX: Track state for Compensation (Saga Pattern)
        boolean limitReserved = false;
        UUID cardId = null;
        BigDecimal amount = request.getAmount();

        try {
            // 1. Idempotency Guard (Fail Fast)
            // FIXED: Removed Duration argument. Service uses internal static TTL.
            idempotencyService.check(idempotencyKey);

            log.info("Processing Card Txn | ExtID: {} | Merchant: {}",
                    request.getExternalTransactionId(), request.getMerchantName());

            // 2. Blind Index Lookup (O(1) Search)
            String panFingerprint = AttributeEncryptor.blindIndex(request.getPan());
            VirtualCard card = virtualCardRepository.findByPanFingerprint(panFingerprint)
                    .orElseThrow(() -> {
                        log.warn("Card Not Found (Invalid PAN) | ExtID: {}", request.getExternalTransactionId());
                        return new BadCredentialsException("Invalid PAN or Card not found");
                    });

            cardId = card.getId(); // FIX: Capture ID for potential rollback

            // 3. Rate Limit Check (Per Card)
            rateLimiterService.checkLimit(card.getId().toString(), "card_transaction", RATE_LIMIT_QUOTA);

            // 4. Crypto Validation (CVV & Expiry)
            validateCardSecurity(card, request);

            // 5. Status Validation (Frozen/Terminated)
            if (card.getStatus() != CardStatus.ACTIVE) {
                log.warn("Transaction Rejected: Card is {} | ID: {}", card.getStatus(), card.getId());
                throw new AccessDeniedException("Card is " + card.getStatus());
            }

            // 6. Velocity/Limit Check (Redis Accumulator)
            // FIX: This now performs Atomic Increment (Reservation) via Lua Script.
            // If this passes, the money is considered "spent" in Redis.
            cardLimitService.checkSpendLimit(card.getId(), card.getMonthlyLimit(), request.getAmount());
            limitReserved = true; // FIX: Mark reservation as successful

            // 7. Ledger Execution (The Money Move)
            String description = String.format("POS: %s | MCC: %s",
                    request.getMerchantName(),
                    request.getMerchantCategoryCode() != null ? request.getMerchantCategoryCode() : "0000");

            // Generate deterministic Reference ID from External ID
            UUID referenceId = UUID.nameUUIDFromBytes(
                    request.getExternalTransactionId().getBytes(StandardCharsets.UTF_8)
            );

            // FIXED: Wrapped arguments in CreateLedgerEntryRequest record
            CreateLedgerEntryRequest ledgerRequest = new CreateLedgerEntryRequest(
                    card.getAccount().getId(),      // accountId
                    request.getAmount(),            // amount
                    request.getCurrency(),          // currency
                    TransactionType.DEBIT,          // type
                    description,                    // description
                    LocalDateTime.now(),            // timestamp (for audit)
                    referenceId                     // referenceId
            );

            ledgerService.recordEntry(ledgerRequest);

            // 8. Update Accumulator (Commit the Spend)
            // FIX: REMOVED to prevent Double-Spending.
            // Atomic increment happened in Step 6 via Lua.
            // cardLimitService.recordSpend(card.getId(), request.getAmount());

            // 9. Complete Idempotency
            // FIXED: Added the transactionId value to comply with method signature
            idempotencyService.complete(idempotencyKey, referenceId.toString());

            log.info("Transaction Approved | ExtID: {} | Amount: {}",
                    request.getExternalTransactionId(), request.getAmount());

            return CardTransactionResponse.builder()
                    .transactionId(referenceId)
                    .status(PaymentStatus.APPROVED)
                    .approvalCode("00" + (System.currentTimeMillis() % 10000)) // Simulated Auth Code
                    .timestamp(LocalDateTime.now())
                    .build();

        } catch (IdempotencyException e) {
            // Rethrow to allow GlobalExceptionHandler to handle (409 Conflict)
            throw e;
        } catch (Exception e) {
            // FIX: Compensation Logic (The "Zombie" Defense)
            // If DB failed (Step 7) but Redis was incremented (Step 6), we MUST roll it back.
            // We exclude CardLimitExceededException because that means the reservation never happened.
            // Logic: If limitReserved is true, cardId is guaranteed non-null by the preceding flow.
            if (limitReserved && !(e instanceof com.opayque.api.infrastructure.exception.CardLimitExceededException)) {
                log.warn("Compensating Transaction: Rolling back Redis spend | ExtID: {} | CardID: {}",
                        request.getExternalTransactionId(), cardId);
                cardLimitService.rollbackSpend(cardId, amount);
            }

            log.error("Transaction Failed | ExtID: {}", request.getExternalTransactionId(), e);
            throw e;
        }
    }

    /**
     * Decrypts DB values and compares them with the cleartext request.
     */
    private void validateCardSecurity(VirtualCard card, CardTransactionRequest request) {
        String decryptedCvv = attributeEncryptor.convertToEntityAttribute(card.getCvv());
        String decryptedExpiry = attributeEncryptor.convertToEntityAttribute(card.getExpiryDate());

        if (!decryptedCvv.equals(request.getCvv())) {
            log.warn("Security Alert: Invalid CVV | CardID: {}", card.getId());
            throw new BadCredentialsException("Invalid CVV");
        }

        if (!decryptedExpiry.equals(request.getExpiryDate())) {
            log.warn("Security Alert: Invalid Expiry | CardID: {}", card.getId());
            throw new BadCredentialsException("Invalid Expiry Date");
        }
    }
}