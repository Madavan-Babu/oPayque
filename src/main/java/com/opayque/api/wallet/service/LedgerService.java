package com.opayque.api.wallet.service;

import com.opayque.api.integration.currency.CurrencyExchangeService;
import com.opayque.api.wallet.dto.CreateLedgerEntryRequest;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.LedgerEntry;
import com.opayque.api.wallet.entity.TransactionType;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.repository.LedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.joda.money.BigMoney;
import org.joda.money.CurrencyUnit;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Orchestrates immutable financial transactions within the oPayque wallet ecosystem.
 *
 * <p>This service guarantees atomic, auditable ledger entries by coordinating pessimistic locking
 * on {@link Account} records, currency conversion via {@link CurrencyExchangeService}, and
 * idempotent persistence of {@link LedgerEntry} entities. It is the single write-entry point
 * for all monetary movements, ensuring that balances are always derived from the authoritative
 * ledger rather than mutable account fields.
 *
 * <p>Concurrency is handled through a combination of database-level pessimistic locks
 * (acquired by {@link AccountRepository#findByIdForUpdate(UUID)}) and optimistic retries
 * (Spring Retry) to gracefully absorb transient contention spikes up to 50 concurrent threads.
 *
 * <p>Currency normalization is automatically applied: if the transaction currency differs
 * from the account’s base currency, the amount is converted using mid-market rates
 * fetched from {@link CurrencyExchangeService}. All monetary values are persisted
 * with a scale of 4 to match the database schema.
 *
 * @author Madavan Babu
 * @since 2026
 *
 * @see LedgerRepository
 * @see CurrencyExchangeService
 * @see AccountRepository
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerService {

    private final LedgerRepository ledgerRepository;
    private final AccountRepository accountRepository;
    private final CurrencyExchangeService exchangeService;

    // REMOVED: EntityManager (Not needed for the clean locking strategy)

    /**
     * Persists a new {@link LedgerEntry} for the supplied {@link CreateLedgerEntryRequest}.
     * <p>
     * This method orchestrates the complete transactional flow: it locks the target {@link Account}
     * with a pessimistic write, performs currency conversion when required, derives the transaction
     * direction, and stores the immutable entry in the ledger table. A retry mechanism guards against
     * concurrent update collisions.
     *
     * @param request immutable data container describing the entry to create
     * @return the fully-persisted ledger entry, including generated id and timestamps
     * @throws IllegalArgumentException if the referenced account does not exist
     * @throws ObjectOptimisticLockingFailureException when concurrent modifications are detected
     * @see LedgerEntry
     * @see Account
     * @see CurrencyExchangeService
     */
    @Transactional
    @Retryable(
            retryFor = { ObjectOptimisticLockingFailureException.class },
            maxAttempts = 12, // Bumped to 10 to handle the 50-thread burst
            backoff = @Backoff(delay = 150, multiplier = 1.5, maxDelay = 2000)
    )
    public LedgerEntry recordEntry(CreateLedgerEntryRequest request) {
        log.debug("Processing Ledger Entry for Account: {}", request.accountId());

        // 1. Fetch Account (Locked by PESSIMISTIC_WRITE in Repository)
        // With the Hikari Pool fix in the test, threads will queue up here
        // without timing out or fighting over connections.
        Account account = accountRepository.findByIdForUpdate(request.accountId())
                .orElseThrow(() -> {
                    log.error("Ledger Failure: Account ID {} not found", request.accountId());
                    return new IllegalArgumentException("Account not found");
                });

        // NOTE: We do NOT touch account.getUser().
        // By leaving it alone, it remains a Hibernate Proxy.
        // Hibernate skips dirty checking on uninitialized proxies, preventing the User version conflict.

        // 2. Determine Currency Logic
        String sourceCurrency = request.currency();
        String targetCurrency = account.getCurrencyCode();

        BigDecimal finalAmount;
        BigDecimal exchangeRate;
        BigDecimal originalAmount = request.amount();
        String originalCurrency = sourceCurrency;

        if (sourceCurrency.equals(targetCurrency)) {
            // FIX: Force Scale 4 even on direct match to ensure DB consistency
            finalAmount = request.amount().setScale(4, RoundingMode.HALF_EVEN);
            exchangeRate = BigDecimal.ONE;
            log.trace("Currency Match ({}) - Skipping Exchange Service", sourceCurrency);
        } else {
            log.info("Currency Mismatch: Converting {} -> {}", sourceCurrency, targetCurrency);
            exchangeRate = exchangeService.getRate(sourceCurrency, targetCurrency);

            BigMoney sourceMoney = BigMoney.of(CurrencyUnit.of(sourceCurrency), originalAmount);
            BigMoney convertedMoney = sourceMoney.convertedTo(CurrencyUnit.of(targetCurrency), exchangeRate);


            // FIX: Force Scale 4 (Matches @Column(scale=4) in LedgerEntry)
            finalAmount = convertedMoney.toMoney(RoundingMode.HALF_EVEN)
                    .getAmount()
                    .setScale(4, RoundingMode.HALF_EVEN);

            log.info("Conversion Applied: {} {} * {} = {} {}",
                    originalAmount, sourceCurrency, exchangeRate, finalAmount, targetCurrency);
        }

        // 3. Construct the Immutable Entry
        // FIX: Auto-derive Direction from Transaction Type
        LedgerEntry entry = LedgerEntry.builder()
                .account(account)
                .amount(finalAmount)
                .currency(targetCurrency)
                .transactionType(request.type())
                .direction(request.type() == TransactionType.CREDIT ? "IN" : "OUT")
                .description(request.description())
                .originalAmount(originalAmount)
                .originalCurrency(originalCurrency)
                .exchangeRate(exchangeRate)
                .recordedAt(LocalDateTime.now())
                .referenceId(request.referenceId())
                .build();

        // 4. Persist
        LedgerEntry saved = ledgerRepository.save(entry);

        log.info("Ledger Recorded: ID={} | Type={} | Amt={} | Ref={}",
                saved.getId(), saved.getTransactionType(), saved.getAmount(), saved.getDescription());

        return saved;
    }

    /**
     * Computes the current balance for the requested {@link Account}.
     * <p>
     * This method delegates to the underlying {@link LedgerRepository} which performs
     * a zero-sum aggregation of {@link LedgerEntry} records associated with the account.
     * The query treats {@code CREDIT} entries as positive and {@code DEBIT} entries as
     * negative, ensuring the balance is always up-to-date without relying on a mutable
     * balance column.
     *
     * @param accountId the unique identifier of the account whose balance is required
     * @return the calculated balance as a {@code BigDecimal}; returns {@code BigDecimal.ZERO}
     *         if no ledger entries exist for the account
     * @see LedgerEntry
     * @see LedgerRepository
     */
    @Transactional(readOnly = true)
    public BigDecimal calculateBalance(java.util.UUID accountId) {
        BigDecimal balance = ledgerRepository.getBalance(accountId);
        return balance != null ? balance : BigDecimal.ZERO;
    }
}