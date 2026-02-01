package com.opayque.api.wallet.service;

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

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private LedgerRepository ledgerRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CurrencyExchangeService exchangeService;

    @InjectMocks
    private LedgerService ledgerService;

    // --- TEST 1: SECURITY (TIME TRAVEL) ---
    @Test
    @DisplayName("Security: Service must enforce System Time (No Backdating)")
    void recordEntry_ShouldIgnoreUserProvidedTimestamp() {
        // Arrange
        UUID accountId = UUID.randomUUID();
        Account mockAccount = new Account();
        mockAccount.setId(accountId);
        mockAccount.setCurrencyCode("USD");

        // Malicious request trying to backdate to 1999
        CreateLedgerEntryRequest request = new CreateLedgerEntryRequest(
                accountId,
                new BigDecimal("100.00"),
                "USD",
                TransactionType.CREDIT,
                "Initial Deposit",
                LocalDateTime.of(1999, 1, 1, 0, 0) // <--- Malicious Input
        );

        when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(mockAccount));
        // Mock save to return the entry passed to it
        when(ledgerRepository.save(any(LedgerEntry.class))).thenAnswer(i -> i.getArguments()[0]);

        // Act
        LedgerEntry saved = ledgerService.recordEntry(request);

        // Assert
        // The recorded time should be NOW, not 1999
        assertThat(saved.getRecordedAt()).isAfter(LocalDateTime.now().minusSeconds(2));
        assertThat(saved.getRecordedAt()).isNotEqualTo(request.timestamp());
    }

    // --- TEST 2: LOGIC (CURRENCY CONVERSION) ---
    @Test
    @DisplayName("Logic: Should convert currency using Exchange Rate and Joda Money math")
    void recordEntry_ShouldConvertCurrency() {
        // Arrange
        UUID accountId = UUID.randomUUID();
        Account eurAccount = new Account();
        eurAccount.setId(accountId);
        eurAccount.setCurrencyCode("EUR"); // Wallet is in EUR

        BigDecimal sourceAmountUSD = new BigDecimal("100.00");
        BigDecimal exchangeRate = new BigDecimal("0.850000"); // 1 USD = 0.85 EUR

        // Request is in USD
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

        // Act
        LedgerEntry saved = ledgerService.recordEntry(request);

        // Assert
        // 1. Verify Joda Money Logic: 100 * 0.85 = 85.00
        BigDecimal expectedAmount = BigMoney.of(CurrencyUnit.USD, sourceAmountUSD)
                .convertedTo(CurrencyUnit.EUR, exchangeRate)
                .getAmount();

        assertThat(saved.getAmount()).isEqualByComparingTo(expectedAmount); // Should be 85.00
        assertThat(saved.getOriginalAmount()).isEqualByComparingTo(sourceAmountUSD);
        assertThat(saved.getOriginalCurrency()).isEqualTo("USD");
        assertThat(saved.getExchangeRate()).isEqualByComparingTo(exchangeRate);
    }

    // --- TEST 3: OPTIMIZATION (SAME CURRENCY) ---
    @Test
    @DisplayName("Optimization: Same currency transactions should skip Exchange Service")
    void recordEntry_SameCurrency_ShouldNotCallExchange() {
        // Arrange
        UUID accountId = UUID.randomUUID();
        Account usdAccount = new Account();
        usdAccount.setId(accountId);
        usdAccount.setCurrencyCode("USD");

        // Request is also USD
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

        // Act
        LedgerEntry saved = ledgerService.recordEntry(request);

        // Assert
        assertThat(saved.getAmount()).isEqualByComparingTo("50.00");
        assertThat(saved.getExchangeRate()).isEqualByComparingTo("1.000000"); // Default 1.0

        // CRITICAL CHECK: Ensure we didn't waste network calls
        verify(exchangeService, never()).getRate(any(), any());
    }

    // --- TEST 4: SAD PATH (ACCOUNT NOT FOUND) ---
    @Test
    @DisplayName("Sad Path: Should throw exception when Account is not found")
    void recordEntry_AccountNotFound_ShouldThrowException() {
        // Arrange
        UUID missingId = UUID.randomUUID();
        CreateLedgerEntryRequest request = new CreateLedgerEntryRequest(
                missingId,
                BigDecimal.TEN,
                "USD",
                TransactionType.CREDIT,
                "Ghost Transaction",
                null
        );

        // Mock the "Empty" response
        when(accountRepository.findByIdForUpdate(missingId)).thenReturn(Optional.empty());

        // Act & Assert
        // This covers the .orElseThrow(() -> ... log.error ... exception) block
        assertThatThrownBy(() -> ledgerService.recordEntry(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Account not found");
    }

    // --- TEST 5: EDGE CASE (NULL BALANCE) ---
    @Test
    @DisplayName("Edge Case: Calculate Balance should return ZERO if repository returns null")
    void calculateBalance_NullReturn_ShouldReturnZero() {
        // Arrange
        UUID accountId = UUID.randomUUID();

        // Simulate a fresh account with no entries (SUM returns null in SQL)
        when(ledgerRepository.getBalance(accountId)).thenReturn(null);

        // Act
        BigDecimal balance = ledgerService.calculateBalance(accountId);

        // Assert
        // This covers the "return balance != null ? balance : BigDecimal.ZERO;" line
        assertThat(balance).isEqualByComparingTo(BigDecimal.ZERO);
    }
}