package com.opayque.api.admin.service;

import com.opayque.api.admin.dto.MoneyDepositRequest;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.infrastructure.ratelimit.RateLimiterService;
import com.opayque.api.wallet.dto.CreateLedgerEntryRequest;
import com.opayque.api.wallet.entity.LedgerEntry;
import com.opayque.api.wallet.entity.TransactionType;
import com.opayque.api.wallet.service.AccountService;
import com.opayque.api.wallet.service.LedgerService;
import net.jqwik.api.*;
import net.jqwik.api.constraints.BigRange;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Tag("pbt")
class AdminWalletServicePropertyTest {

    private AdminWalletService adminWalletService;
    private AccountService accountService;
    private RateLimiterService rateLimiterService;
    private UserRepository userRepository;
    private LedgerService ledgerService;

    private final String ADMIN_EMAIL = "property-test-admin@opayque.com";
    private final UUID ADMIN_ID = UUID.randomUUID();
    private final UUID TARGET_ACCOUNT_ID = UUID.randomUUID();

    /**
     * MANUAL SETUP: Guarantees a fresh environment for every single property trial.
     * Prevents "TooManyActualInvocations" by ensuring mocks never accumulate history.
     */
    private void init() {
        accountService = mock(AccountService.class);
        rateLimiterService = mock(RateLimiterService.class);
        userRepository = mock(UserRepository.class);
        ledgerService = mock(LedgerService.class);

        // Explicitly instantiate with the FRESH mocks
        adminWalletService = new AdminWalletService(
                accountService,
                rateLimiterService,
                userRepository,
                ledgerService
        );

        User mockAdmin = User.builder().id(ADMIN_ID).email(ADMIN_EMAIL).build();
        when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(mockAdmin));
    }

    @Property(tries = 100)
    @Label("Invariant: All Deposits MUST be of type CREDIT and contain Audit Prefix")
    void allDepositsMustBeCreditAndAudited(@ForAll("validDepositRequests") MoneyDepositRequest request) {
        init(); // <--- CRITICAL: Resets state for this specific trial

        when(ledgerService.recordEntry(any())).thenReturn(LedgerEntry.builder().build());

        // Act
        adminWalletService.depositFunds(ADMIN_EMAIL, TARGET_ACCOUNT_ID, request);

        // Assert
        ArgumentCaptor<CreateLedgerEntryRequest> captor = ArgumentCaptor.forClass(CreateLedgerEntryRequest.class);
        verify(ledgerService, times(1)).recordEntry(captor.capture());
        CreateLedgerEntryRequest capturedRequest = captor.getValue();

        assertThat(capturedRequest.type()).isEqualTo(TransactionType.CREDIT);
        assertThat(capturedRequest.description()).startsWith("ADMIN_DEPOSIT: ");
        assertThat(capturedRequest.amount()).isEqualByComparingTo(request.amount());
    }

    @Property(tries = 50)
    @Label("Security: Rate Limiter is ALWAYS checked with correct key and quota")
    void rateLimiterIsAlwaysInvoked(@ForAll("validDepositRequests") MoneyDepositRequest request) {
        init(); // <--- CRITICAL: Resets state

        when(ledgerService.recordEntry(any())).thenReturn(LedgerEntry.builder().build());

        adminWalletService.depositFunds(ADMIN_EMAIL, TARGET_ACCOUNT_ID, request);

        // Verify the security gate was hit exactly once for this trial
        verify(rateLimiterService, times(1))
                .checkLimit(eq(ADMIN_ID.toString()), eq("admin_deposit"), eq(5L));
    }

    @Property(tries = 50)
    @Label("Robustness: Null Description is handled gracefully (Defaults to 'Manual Top-Up')")
    void handlesNullDescriptionGracefully(
            @ForAll @BigRange(min = "0.01", max = "1000000.00") BigDecimal amount,
            @ForAll("validCurrency") String currency) {

        init(); // <--- CRITICAL: Resets state

        MoneyDepositRequest request = new MoneyDepositRequest(amount, currency, null);
        when(ledgerService.recordEntry(any())).thenReturn(LedgerEntry.builder().build());

        adminWalletService.depositFunds(ADMIN_EMAIL, TARGET_ACCOUNT_ID, request);

        ArgumentCaptor<CreateLedgerEntryRequest> captor = ArgumentCaptor.forClass(CreateLedgerEntryRequest.class);
        verify(ledgerService, times(1)).recordEntry(captor.capture());

        assertThat(captor.getValue().description())
                .isEqualTo("ADMIN_DEPOSIT: Manual Top-Up");
    }

    // --- DATA GENERATORS ---

    @Provide
    Arbitrary<MoneyDepositRequest> validDepositRequests() {
        return Combinators.combine(
                validAmount(),
                validCurrency(),
                validDescription()
        ).as(MoneyDepositRequest::new);
    }

    @Provide
    Arbitrary<BigDecimal> validAmount() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.valueOf(0.01), BigDecimal.valueOf(1_000_000_000))
                .ofScale(2);
    }

    @Provide
    Arbitrary<String> validCurrency() {
        // Strictly limited to supported IBAN currencies
        return Arbitraries.of("EUR", "GBP", "CHF");
    }

    @Provide
    Arbitrary<String> validDescription() {
        return Arbitraries.strings()
                .alpha().numeric()
                .ofMinLength(0).ofMaxLength(100)
                .injectNull(0.1);
    }
}