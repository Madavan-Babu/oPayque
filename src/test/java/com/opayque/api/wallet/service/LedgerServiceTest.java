package com.opayque.api.wallet.service;

import com.opayque.api.infrastructure.exception.GlobalExceptionHandler;
import com.opayque.api.integration.currency.CurrencyExchangeService;
import com.opayque.api.wallet.dto.CreateLedgerEntryRequest;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.LedgerEntry;
import com.opayque.api.wallet.entity.TransactionType;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.repository.LedgerRepository;
import org.joda.money.BigMoney;
import org.joda.money.CurrencyUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/// **Behavioral Audit Suite for LedgerService — Business Logic and Financial Integrity**.
///
/// This test suite validates the core operational logic of the [LedgerService], ensuring that
/// financial movements are processed with high precision, security-first temporal constraints,
/// and optimized cross-currency workflows.
///
/// **Tested Domains:**
/// * **Security:** Prevention of backdating and temporal tampering via system-time enforcement.
/// * **Financial Math:** Precise currency conversion using `Joda-Money` and [BigDecimal].
/// * **Optimization:** Intelligent bypass of the exchange rate engine for same-currency operations.
/// * **Error Handling:** Graceful failure modes for missing entities and null repository returns.
///
@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    /// Primary repository for persisting immutable ledger records.
    @Mock
    private LedgerRepository ledgerRepository;

    /// Account persistence layer utilized for pessimistic locking and state validation.
    @Mock
    private AccountRepository accountRepository;

    /// External/Internal gateway for retrieving real-time exchange rates.
    @Mock
    private CurrencyExchangeService exchangeService;

    /// The service under test, with mocked dependencies injected via constructor.
    @InjectMocks
    private LedgerService ledgerService;

    /// **Security Audit: Temporal Gatekeeping**.
    ///
    /// Verifies that the service strictly utilizes the server's [LocalDateTime] for ledger
    /// entries, overriding any user-supplied timestamps to prevent "Time Travel" attacks or
    /// fraudulent backdating of transaction history.
    @Test
    @DisplayName("Security: Service must enforce System Time (No Backdating)")
    void recordEntry_ShouldIgnoreUserProvidedTimestamp() {
        // Arrange: Construct a malicious payload attempting to backdate a transaction to 1999
        UUID accountId = UUID.randomUUID();
        Account mockAccount = new Account();
        mockAccount.setId(accountId);
        mockAccount.setCurrencyCode("USD");

        CreateLedgerEntryRequest request = new CreateLedgerEntryRequest(
                accountId,
                new BigDecimal("100.00"),
                "USD",
                TransactionType.CREDIT,
                "Initial Deposit",
                LocalDateTime.of(1999, 1, 1, 0, 0)
        );

        when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(mockAccount));
        // Capture and return the entity to inspect its internal state after service processing
        when(ledgerRepository.save(any(LedgerEntry.class))).thenAnswer(i -> i.getArguments()[0]);

        // Act: Invoke the entry recording logic
        LedgerEntry saved = ledgerService.recordEntry(request);

        // Assert: Ensure the recorded timestamp aligns with the current system window, not the malicious input
        assertThat(saved.getRecordedAt()).isAfter(LocalDateTime.now().minusSeconds(2));
        assertThat(saved.getRecordedAt()).isNotEqualTo(request.timestamp());
    }

    /// **Financial Integrity: High-Precision Currency Conversion**.
    ///
    /// Validates the integration between the ledger engine and the [CurrencyExchangeService].
    /// This test ensures that [BigMoney] logic is correctly applied to prevent cent-level
    /// rounding errors during multi-currency fund movements.
    @Test
    @DisplayName("Logic: Should convert currency using Exchange Rate and Joda Money math")
    void recordEntry_ShouldConvertCurrency() {
        // Arrange: Setup a cross-currency scenario (USD deposit into a EUR account)
        UUID accountId = UUID.randomUUID();
        Account eurAccount = new Account();
        eurAccount.setId(accountId);
        eurAccount.setCurrencyCode("EUR");

        BigDecimal sourceAmountUSD = new BigDecimal("100.00");
        BigDecimal exchangeRate = new BigDecimal("0.850000"); // 1 USD = 0.85 EUR

        CreateLedgerEntryRequest request = new CreateLedgerEntryRequest(
                accountId,
                sourceAmountUSD,
                "USD",
                TransactionType.CREDIT,
                "Transfer",
                null
        );

        when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(eurAccount));
        when(exchangeService.getRate("USD", "EUR")).thenReturn(exchangeRate);
        when(ledgerRepository.save(any(LedgerEntry.class))).thenAnswer(i -> i.getArguments()[0]);

        // Act: Execute the multi-currency recording logic
        LedgerEntry saved = ledgerService.recordEntry(request);

        // Assert: Verify the converted amount matches the expected Joda Money calculation
        BigDecimal expectedAmount = BigMoney.of(CurrencyUnit.USD, sourceAmountUSD)
                .convertedTo(CurrencyUnit.EUR, exchangeRate)
                .getAmount();

        assertThat(saved.getAmount()).isEqualByComparingTo(expectedAmount); // 100 USD * 0.85 = 85.00 EUR
        assertThat(saved.getOriginalAmount()).isEqualByComparingTo(sourceAmountUSD);
        assertThat(saved.getOriginalCurrency()).isEqualTo("USD");
        assertThat(saved.getExchangeRate()).isEqualByComparingTo(exchangeRate);
    }

    /// **Performance Optimization: Operational Short-Circuiting**.
    ///
    /// Confirms that the service is intelligent enough to skip the exchange rate engine
    /// when the source and destination currencies are identical, preserving network
    /// bandwidth and reducing transaction latency.
    @Test
    @DisplayName("Optimization: Same currency transactions should skip Exchange Service")
    void recordEntry_SameCurrency_ShouldNotCallExchange() {
        // Arrange: Same-currency transaction (USD into USD account)
        UUID accountId = UUID.randomUUID();
        Account usdAccount = new Account();
        usdAccount.setId(accountId);
        usdAccount.setCurrencyCode("USD");

        CreateLedgerEntryRequest request = new CreateLedgerEntryRequest(
                accountId,
                new BigDecimal("50.00"),
                "USD",
                TransactionType.DEBIT,
                "Payment",
                null
        );

        when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(usdAccount));
        when(ledgerRepository.save(any(LedgerEntry.class))).thenAnswer(i -> i.getArguments()[0]);

        // Act: Execute the single-currency recording
        LedgerEntry saved = ledgerService.recordEntry(request);

        // Assert: Verify rate is unity and the external exchange service was never invoked
        assertThat(saved.getAmount()).isEqualByComparingTo("50.00");
        assertThat(saved.getExchangeRate()).isEqualByComparingTo("1.000000");

        // CRITICAL CHECK: Network overhead prevention audit
        verify(exchangeService, never()).getRate(any(), any());
    }

    /// **Reliability Audit: Identity Validation Failure**.
    ///
    /// Verifies that the service rejects ledger requests targeting non-existent accounts,
    /// triggering appropriate exception flow to the [GlobalExceptionHandler].
    @Test
    @DisplayName("Sad Path: Should throw exception when Account is not found")
    void recordEntry_AccountNotFound_ShouldThrowException() {
        // Arrange: Target a randomized, non-existent UUID
        UUID missingId = UUID.randomUUID();
        CreateLedgerEntryRequest request = new CreateLedgerEntryRequest(
                missingId,
                BigDecimal.TEN,
                "USD",
                TransactionType.CREDIT,
                "Ghost Transaction",
                null
        );

        when(accountRepository.findByIdForUpdate(missingId)).thenReturn(Optional.empty());

        // Act & Assert: Verify the business exception is thrown with appropriate context
        assertThatThrownBy(() -> ledgerService.recordEntry(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Account not found");
    }

    /// **Resilience Audit: Null-Safety and State Derivation**.
    ///
    /// Ensures that balance calculations remain resilient to database `NULL` returns
    /// (common when performing `SUM` on fresh accounts), correctly normalizing to zero
    /// to prevent NPEs in the API response layer.
    @Test
    @DisplayName("Edge Case: Calculate Balance should return ZERO if repository returns null")
    void calculateBalance_NullReturn_ShouldReturnZero() {
        // Arrange: Simulate a fresh account with no historical entries
        UUID accountId = UUID.randomUUID();
        when(ledgerRepository.getBalance(accountId)).thenReturn(null);

        // Act: Calculate derived balance
        BigDecimal balance = ledgerService.calculateBalance(accountId);

        // Assert: Verify null-to-zero normalization logic
        assertThat(balance).isEqualByComparingTo(BigDecimal.ZERO);
    }
}