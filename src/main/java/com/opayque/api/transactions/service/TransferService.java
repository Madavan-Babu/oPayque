package com.opayque.api.transactions.service;

import com.opayque.api.infrastructure.exception.InsufficientFundsException;
import com.opayque.api.infrastructure.idempotency.IdempotencyService;
import com.opayque.api.wallet.dto.CreateLedgerEntryRequest;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.TransactionType;
import com.opayque.api.wallet.service.AccountService;
import com.opayque.api.wallet.service.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Core settlement service for peer-to-peer (P2P) wallet transfers.
 * <p>
 * Guarantees exactly-once execution via deterministic idempotency keys,
 * double-entry ledger integrity, and pessimistic balance validation.
 * All operations are ACID within a single {@link Transactional} boundary.
 * </p>
 * <p>
 * Thread-safe under the assumption that {@link AccountService#getAccountById}
 * acquires row-level locks on the account record.
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class TransferService {

    private final AccountService accountService;
    private final LedgerService ledgerService;
    /**
     * Idempotency guardrail preventing duplicate debits/credits in distributed
     * environments (mobile replay, webhook retries, MQ redelivery).
     */
    private final IdempotencyService idempotencyService;

    /**
     * Atomically transfers funds between two wallets denominated in the same currency.
     * <p>
     * Pre-conditions:
     * <ul>
     *   <li>Sender and receiver must be distinct KYC-verified users.</li>
     *   <li>Sender must possess sufficient settled balance.</li>
     *   <li>Receiver must maintain a wallet for the requested currency.</li>
     * </ul>
     * </p>
     * <p>
     * Post-conditions:
     * <ul>
     *   <li>Sender balance decreased by amount; receiver balance increased by amount.</li>
     *   <li>Two immutable ledger entries created with identical transferId.</li>
     *   <li>Idempotency key marked COMPLETED to prevent duplicate application.</li>
     * </ul>
     * </p>
     *
     * @param senderId        UUID of the originating wallet owner
     * @param receiverEmail   Unique e-mail identifier of the beneficiary
     * @param amountStr       Decimal string in unit currency; must be positive
     * @param currency        ISO-4217 alphabetic code
     * @param idempotencyKey  Client-supplied UUIDv4 or SHA-256 hash for at-least-once safety
     * @return Universally unique identifier for the settled transfer
     * @throws IllegalArgumentException   on malformed amount, self-transfer, or missing wallet
     * @throws InsufficientFundsException when sender's available balance < amount
     * @throws IllegalStateException      on data integrity violation (orphaned account)
     */
    public UUID transferFunds(UUID senderId, String receiverEmail, String amountStr, String currency, String idempotencyKey) {
        // 2. LOCK: Fail Fast if duplicate
        idempotencyService.lock(idempotencyKey);

        log.info("Initiating Transfer: Sender={} -> Receiver={} | Key={}", senderId, receiverEmail, idempotencyKey);

        try {
            // Parse and validate monetary amount to prevent precision loss
            BigDecimal amount = new BigDecimal(amountStr);
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                // SECURITY: Log this. Negative transfers are often exploit attempts.
                log.warn("Transfer rejected: Non-positive amount [{}] from sender [{}]", amount, senderId);
                throw new IllegalArgumentException("Transfer amount must be greater than zero");
            }

            // Acquire pessimistic lock on sender wallet to serialize concurrent debits
            Account senderAccount = accountService.getAccountForUpdate(senderId); // Forces the lock

            // Locate beneficiary wallet; reject if currency corridor unavailable
            List<Account> receiverAccounts = accountService.getAccountsForUser(receiverEmail);
            Account receiverAccount = receiverAccounts.stream()
                    .filter(acc -> acc.getCurrencyCode().equals(currency))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Receiver does not have a wallet for currency: " + currency));

            if (senderAccount.getUser() == null || receiverAccount.getUser() == null) {
                log.error("Integrity Error: Account found without associated User.");
                throw new IllegalStateException("Account data integrity violation.");
            }

            if (senderAccount.getUser().getId().equals(receiverAccount.getUser().getId())) {
                throw new IllegalArgumentException("Cannot transfer to self");
            }

            // Real-time balance check against ledger to enforce spend-authority
            BigDecimal senderBalance = ledgerService.calculateBalance(senderId);
            if (senderBalance.compareTo(amount) < 0) {
                log.warn("Transfer Failed: Insufficient Funds. Balance={}, Req={}", senderBalance, amount);
                throw new InsufficientFundsException("Insufficient funds for transfer.");
            }

            UUID transferId = UUID.randomUUID();

            // Post double-entry ledger: debit sender, credit receiver
            ledgerService.recordEntry(new CreateLedgerEntryRequest(
                    senderAccount.getId(), amount, currency, TransactionType.DEBIT,
                    "Transfer to " + receiverEmail, null, transferId
            ));

            ledgerService.recordEntry(new CreateLedgerEntryRequest(
                    receiverAccount.getId(), amount, currency, TransactionType.CREDIT,
                    "Transfer from " + senderAccount.getUser().getEmail(), null, transferId
            ));

            // 3. COMPLETE: Mark as Done
            idempotencyService.complete(idempotencyKey, transferId.toString());
            log.info("Transfer Complete: ID={}", transferId);

            return transferId; // <--- Return Receipt

        } catch (RuntimeException e) {
            log.error("Transfer Logic Failed. Key [{}] remains locked (PENDING) until TTL.", idempotencyKey);
            throw e;
        }
    }
}