package com.opayque.api.admin.service;

import com.opayque.api.admin.dto.MoneyDepositRequest;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.infrastructure.exception.RateLimitExceededException;
import com.opayque.api.infrastructure.ratelimit.RateLimiterService;
import com.opayque.api.wallet.dto.CreateLedgerEntryRequest;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.AccountStatus;
import com.opayque.api.wallet.entity.LedgerEntry;
import com.opayque.api.wallet.entity.TransactionType;
import com.opayque.api.wallet.service.AccountService;
import com.opayque.api.wallet.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminWalletServiceTest {

    @Mock private AccountService accountService;
    @Mock private RateLimiterService rateLimiterService;
    @Mock private UserRepository userRepository;
    @Mock private LedgerService ledgerService;

    @InjectMocks
    private AdminWalletService adminWalletService;

    private User mockAdmin;
    private final String ADMIN_EMAIL = "godmode@opayque.com";
    private final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID TARGET_ACCOUNT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockAdmin = User.builder()
                .id(ADMIN_ID)
                .email(ADMIN_EMAIL)
                .fullName("Super Admin")
                .build();
    }

    // =========================================================================
    // FEATURE 1: ACCOUNT STATUS UPDATE
    // =========================================================================

    @Test
    @DisplayName("Status Update: Should succeed when Admin is valid and Rate Limit OK")
    void updateAccountStatus_HappyPath() {
        // Arrange
        AccountStatus newStatus = AccountStatus.FROZEN;
        Account updatedAccount = Account.builder().id(TARGET_ACCOUNT_ID).status(newStatus).build();

        when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(mockAdmin));
        when(accountService.updateAccountStatus(TARGET_ACCOUNT_ID, newStatus, true))
                .thenReturn(updatedAccount);

        // Act
        Account result = adminWalletService.updateAccountStatus(ADMIN_EMAIL, TARGET_ACCOUNT_ID, newStatus);

        // Assert
        assertThat(result.getStatus()).isEqualTo(AccountStatus.FROZEN);

        // Verify Governance
        verify(rateLimiterService).checkLimit(ADMIN_ID.toString(), "admin_action", 10);
        // Verify Execution
        verify(accountService).updateAccountStatus(TARGET_ACCOUNT_ID, newStatus, true);
    }

    @Test
    @DisplayName("Status Update: Should throw AccessDenied if Admin User does not exist")
    void updateAccountStatus_WhenAdminNotFound() {
        // Arrange
        when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() ->
                adminWalletService.updateAccountStatus(ADMIN_EMAIL, TARGET_ACCOUNT_ID, AccountStatus.FROZEN)
        )
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Admin context invalid");

        // Verify No Side Effects
        verifyNoInteractions(rateLimiterService);
        verifyNoInteractions(accountService);
    }

    @Test
    @DisplayName("Status Update: Should propagate RateLimitExceededException")
    void updateAccountStatus_WhenRateLimitExceeded() {
        // Arrange
        when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(mockAdmin));
        doThrow(new RateLimitExceededException("Too fast"))
                .when(rateLimiterService).checkLimit(anyString(), anyString(), anyLong());

        // Act & Assert
        assertThatThrownBy(() ->
                adminWalletService.updateAccountStatus(ADMIN_EMAIL, TARGET_ACCOUNT_ID, AccountStatus.FROZEN)
        )
                .isInstanceOf(RateLimitExceededException.class);

        // Verify Service Protection: Core logic never ran
        verifyNoInteractions(accountService);
    }

    // =========================================================================
    // FEATURE 2: DEPOSIT FUNDS ("Fiat God Mode")
    // =========================================================================

    @Test
    @DisplayName("Deposit: Should construct correct CREDIT Ledger Entry (Audit Trail Verification)")
    void depositFunds_HappyPath_WithDescription() {
        // Arrange
        MoneyDepositRequest request = new MoneyDepositRequest(
                new BigDecimal("1000.00"), "EUR", "Stimulus Package"
        );

        LedgerEntry expectedEntry = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .amount(new BigDecimal("1000.00"))
                .transactionType(TransactionType.CREDIT)
                .build();

        when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(mockAdmin));
        when(ledgerService.recordEntry(any())).thenReturn(expectedEntry);

        // Act
        LedgerEntry result = adminWalletService.depositFunds(ADMIN_EMAIL, TARGET_ACCOUNT_ID, request);

        // Assert
        assertThat(result).isNotNull();

        // ---------------------------------------------------------------------
        // CRITICAL: Argument Captor to verify the "Black Box" construction
        // ---------------------------------------------------------------------
        ArgumentCaptor<CreateLedgerEntryRequest> captor = ArgumentCaptor.forClass(CreateLedgerEntryRequest.class);
        verify(ledgerService).recordEntry(captor.capture());
        CreateLedgerEntryRequest capturedRequest = captor.getValue();

        // Verify Financial Integrity
        assertThat(capturedRequest.accountId()).isEqualTo(TARGET_ACCOUNT_ID);
        assertThat(capturedRequest.amount()).isEqualTo(new BigDecimal("1000.00"));
        assertThat(capturedRequest.currency()).isEqualTo("EUR");

        // Verify Direction (Must be CREDIT)
        assertThat(capturedRequest.type()).isEqualTo(TransactionType.CREDIT);

        // Verify Audit Trail Formatting
        assertThat(capturedRequest.description()).isEqualTo("ADMIN_DEPOSIT: Stimulus Package");

        // Verify Governance
        // Note: Quota is 5 for money, 10 for status
        verify(rateLimiterService).checkLimit(ADMIN_ID.toString(), "admin_deposit", 5);
    }

    @Test
    @DisplayName("Deposit: Should use Default Description when none provided")
    void depositFunds_HappyPath_NullDescription() {
        // Arrange
        MoneyDepositRequest request = new MoneyDepositRequest(
                new BigDecimal("500.00"), "EUR", null // <--- Null description, Valid IBAN Currency
        );

        when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(mockAdmin));

        // Act
        adminWalletService.depositFunds(ADMIN_EMAIL, TARGET_ACCOUNT_ID, request);

        // Assert
        ArgumentCaptor<CreateLedgerEntryRequest> captor = ArgumentCaptor.forClass(CreateLedgerEntryRequest.class);
        verify(ledgerService).recordEntry(captor.capture());

        // Verify Default Audit Text
        assertThat(captor.getValue().description()).isEqualTo("ADMIN_DEPOSIT: Manual Top-Up");
    }

    @Test
    @DisplayName("Deposit: Should throw AccessDenied if Admin User invalid")
    void depositFunds_WhenAdminNotFound() {
        // Arrange
        MoneyDepositRequest request = new MoneyDepositRequest(new BigDecimal("100.00"), "USD", "Test");
        when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() ->
                adminWalletService.depositFunds(ADMIN_EMAIL, TARGET_ACCOUNT_ID, request)
        )
                .isInstanceOf(AccessDeniedException.class);

        // Verify Money is SAFE (Ledger never touched)
        verifyNoInteractions(ledgerService);
    }

    @Test
    @DisplayName("Deposit: Should enforce Rate Limit strictness (5/min)")
    void depositFunds_WhenRateLimitExceeded() {
        // Arrange
        MoneyDepositRequest request = new MoneyDepositRequest(new BigDecimal("100.00"), "USD", "Test");
        when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(mockAdmin));

        doThrow(new RateLimitExceededException("Stop printing money!"))
                .when(rateLimiterService).checkLimit(anyString(), eq("admin_deposit"), eq(5L));

        // Act & Assert
        assertThatThrownBy(() ->
                adminWalletService.depositFunds(ADMIN_EMAIL, TARGET_ACCOUNT_ID, request)
        )
                .isInstanceOf(RateLimitExceededException.class);

        // Verify Ledger Safety
        verifyNoInteractions(ledgerService);
    }
}