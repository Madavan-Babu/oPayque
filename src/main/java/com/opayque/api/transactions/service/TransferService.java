package com.opayque.api.transactions.service;

import com.opayque.api.infrastructure.exception.InsufficientFundsException;
import com.opayque.api.wallet.dto.CreateLedgerEntryRequest;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.repository.AccountRepository;
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

/// **Epic 3: Atomic Transaction Engine — High-Precision Fund Orchestrator**.
///
/// This service coordinates complex, multi-stage financial movements between user wallets.
/// It acts as the primary enforcement layer for the project's **"Reliability-First"** mandate,
/// ensuring that money is never created or destroyed during a transfer, only moved.
///
/// **Architectural Constraints:**
/// - **Atomicity:** Decorated with [@Transactional] to ensure that the "Debit" and "Credit"
///   phases are committed as a single, indivisible unit of work.
/// - **Pessimistic Locking:** Inherits locking behavior from the [AccountRepository]
///   invoked via the [AccountService] to prevent "Lost Updates" in high-concurrency scenarios.
/// - **Auditability:** Generates a shared `transferId` (Reference ID) to link opposing
///   ledger entries for future reconciliation.
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional // SATISFIES ARCHUNIT: Ensures Atomicity for all methods
public class TransferService {

    private final AccountService accountService;
    private final LedgerService ledgerService;

    /// **Operational Unit: Atomic P2P Fund Movement**.
    ///
    /// Executes a peer-to-peer transfer by orchestrating a zero-sum movement between two
    /// independent ledger partitions.
    ///
    /// **Execution Pipeline:**
    /// 1. **Validation Gate:** Rejects non-positive amounts to prevent "Negative Spend" attacks.
    /// 2. **Resolution:** Locates the sender's wallet and finds a compatible currency wallet
    ///    for the recipient.
    /// 3. **Integrity Audit:** Verifies that neither account is orphaned (missing user identity)
    ///    and blocks "Narcissistic Transfers" (self-spending).
    /// 4. **Liquidity Check:** Performs a dynamic balance aggregation to verify sufficient funds
    ///    before initiating any write operations.
    /// 5. **Atomic Commit:** Dispatches symmetric `TransactionType.DEBIT` and `TransactionType.CREDIT`
    ///    requests to the [LedgerService].
    ///
    /// **Precision Mandate:**
    /// Utilizes [BigDecimal] for the `amount` parameter to maintain sub-cent precision,
    /// satisfying the project's requirements for financial mathematical safety.
    ///
    /// @param senderId The unique [UUID] of the source account.
    /// @param receiverEmail The verified email address of the target identity.
    /// @param amountStr The transfer value as a string to prevent IEEE 754 floating-point errors
    ///                  during deserialization.
    /// @param currency The ISO 4217 currency code identifying the transaction medium.
    ///
    /// @throws InsufficientFundsException If the sender's balance is lower than the requested amount.
    /// @throws IllegalArgumentException If the amount is $< 0$ or if the receiver has no compatible wallet.
    /// @throws IllegalStateException If a data integrity violation is detected (Orphaned Account).
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
        // The Atomic Pulse: Debit and Credit are created with the SAME transferId.
        // If creditRequest fails, Spring's Proxy will trigger a DB Rollback for debitRequest.
        ledgerService.recordEntry(creditRequest);

        log.info("Transfer Complete: ID={}", transferId);
    }
}