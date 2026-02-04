package com.opayque.api.transactions.service;

import com.opayque.api.infrastructure.exception.InsufficientFundsException;
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

/// Epic 3: Atomic Transaction Engine.
///
/// Coordinate high-level financial movements between users.
/// strictly enforcing ACID compliance and preventing double-spending via proper orchestration.
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional // SATISFIES ARCHUNIT: Ensures Atomicity for all methods
public class TransferService {

    private final AccountService accountService;
    private final LedgerService ledgerService;

    /// Executes a P2P transfer between two users.
    ///
    /// This method wraps two distinct ledger entries (Debit + Credit) into a single
    /// atomic database transaction. If the Credit fails, the Debit rolls back.
    ///
    /// @param senderId The UUID of the sender's wallet.
    /// @param receiverEmail The verified email of the recipient.
    /// @param amountStr The amount to transfer (String to ensure BigDecimal precision).
    /// @param currency The ISO 4217 currency code (currently strictly enforces match).
    public void transferFunds(UUID senderId, String receiverEmail, String amountStr, String currency) {
        BigDecimal amount = new BigDecimal(amountStr);
        log.info("Initiating Transfer: Sender={} -> Receiver={} | Amt={} {}", senderId, receiverEmail, amount, currency);

        // 1. Validation Guardrails
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive");
        }

        // 2. Resolve Accounts (Using Services to respect ArchUnit)
        Account senderAccount = accountService.getAccountById(senderId);

        // For MVP, we assume the receiver has an account in the requested currency.
        // If they have multiple, we pick the first one matching the currency.
        List<Account> receiverAccounts = accountService.getAccountsForUser(receiverEmail);
        Account receiverAccount = receiverAccounts.stream()
                .filter(acc -> acc.getCurrencyCode().equals(currency))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Receiver does not have a wallet for currency: " + currency));

        // 3. Self-Transfer Check
        // HARDENING: Defensive checks to prevent NPEs if Hibernate proxies are uninitialized
        if (senderAccount.getUser() == null || receiverAccount.getUser() == null) {
            log.error("Integrity Error: Account found without associated User. SenderID={}, ReceiverEmail={}", senderId, receiverEmail);
            throw new IllegalStateException("Account data integrity violation.");
        }

        if (senderAccount.getUser().getId().equals(receiverAccount.getUser().getId())) {
            throw new IllegalArgumentException("Cannot transfer to self");
        }

        // 4. Insufficient Funds Check (The Business Rule)
        // We check this BEFORE creating the Debit entry to save DB write operations.
        BigDecimal senderBalance = ledgerService.calculateBalance(senderId);
        if (senderBalance.compareTo(amount) < 0) {
            log.warn("Transfer Failed: Insufficient Funds. Balance={}, Req={}", senderBalance, amount);
            throw new InsufficientFundsException("Insufficient funds for transfer.");
        }

        // 5. Generate The Atomic Link (The Reference ID)
        UUID transferId = UUID.randomUUID();

        // 6. Execute DEBIT (Sender)
        // Note: We pass 'null' for timestamp (Service uses system time) but we MUST pass transferId.
        CreateLedgerEntryRequest debitRequest = new CreateLedgerEntryRequest(
                senderAccount.getId(),
                amount,
                currency,
                TransactionType.DEBIT,
                "Transfer to " + receiverEmail,
                null,       // Timestamp (ignored)
                transferId  // <--- The Link
        );
        ledgerService.recordEntry(debitRequest);

        // 7. Execute CREDIT (Receiver)
        CreateLedgerEntryRequest creditRequest = new CreateLedgerEntryRequest(
                receiverAccount.getId(),
                amount,
                currency,
                TransactionType.CREDIT,
                "Transfer from " + senderAccount.getUser().getEmail(),
                null,       // Timestamp (ignored)
                transferId  // <--- The Link
        );
        ledgerService.recordEntry(creditRequest);

        log.info("Transfer Complete: ID={}", transferId);
    }
}