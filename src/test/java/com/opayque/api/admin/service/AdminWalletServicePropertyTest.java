package com.opayque.api.admin.service;

import com.opayque.api.admin.controller.AdminWalletController;
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

/**
 * <p>This test class validates the {@link AdminWalletService} using property‑based testing
 * to ensure that administrative wallet operations consistently satisfy core business invariants.</p>
 *
 * <p><b>Key responsibilities</b>:</p>
 * <ul>
 *   <li>Guarantees a fresh mock environment for each trial to avoid state leakage
 *       and "TooManyActualInvocations" errors.</li>
 *   <li>Confirms that all deposit ledger entries are of type {@link TransactionType#CREDIT}
 *       and carry the required audit prefix.</li>
 *   <li>Verifies that the {@link RateLimiterService} is invoked with the correct admin identifier,
 *       operation key (<code>"admin_deposit"</code>) and quota (<code>5L</code>) for every deposit
 *       request.</li>
 *   <li>Ensures graceful handling of {@code null} descriptions by defaulting to
 *       <code>"ADMIN_DEPOSIT: Manual Top-Up"</code>.</li>
 * </ul>
 *
 * <p>The class employs Jqwik's {@code @Property} annotation to generate a wide range of
 * {@link MoneyDepositRequest} instances via custom data generators, providing high confidence
 * that the service behaves correctly across diverse inputs.</p>
 *
 * @see AdminWalletService
 * @see AccountService
 * @see RateLimiterService
 * @see UserRepository
 * @see LedgerService
 * @see MoneyDepositRequest
 * @see CreateLedgerEntryRequest
 *
 * @author Madavan Babu
 * @since 2026
 */
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

    /**
     * <p>Validates the core invariant that every deposit performed by {@link AdminWalletService}
     * must be persisted as a {@link TransactionType} of type {@code CREDIT} and that the
     * ledger entry description starts with the required audit prefix.</p>
     *
     * <p>The method is executed as a property‑based test ( {@code @Property(tries = 100)} )
     * and follows these steps:</p>
     * <ul>
     *   <li>Invokes {@link #init()} to reset all mocked collaborators, guaranteeing a clean
     *       state for each trial.</li>
     *   <li>Stubs {@link LedgerService#recordEntry(CreateLedgerEntryRequest)} to return an empty
     *       {@link LedgerEntry LedgerEntry}.</li>
     *   <li>Calls {@link AdminWalletService#depositFunds(String, UUID, MoneyDepositRequest)}
     *       with the supplied {@code request}.</li>
     *   <li>Captures the {@link CreateLedgerEntryRequest} passed to {@link LedgerService#recordEntry}
     *       and asserts that:
     *       <ul>
     *         <li>The {@code type} is {@link TransactionType#CREDIT}.</li>
     *         <li>The {@code description} begins with the prefix {@code "ADMIN_DEPOSIT: " }.</li>
     *         <li>The {@code amount} matches the {@code amount} field of the {@code request}.</li>
     *       </ul>
     *   </li>
     * </ul>
     *
     * <p>This test guarantees that the service layer enforces the business rule that
     * all admin‑initiated deposits are credit transactions and are auditable through
     * a standardized description format.</p>
     *
     * @param request a {@link MoneyDepositRequest} generated by the {@code validDepositRequests()}
     *                provider. It contains a valid {@code amount}, {@code currency} and an
     *                optional {@code description} used to simulate real‑world deposit payloads.
     *
     * @see AdminWalletController
     * @see AdminWalletService
     * @see LedgerService
     */
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

    /**
     * <p>Ensures that the {@link RateLimiterService} security gate is consulted for every
     * admin‑initiated deposit operation. The test confirms that the rate‑limiting check
     * is executed exactly once with the correct identifier, key, and quota, thereby
     * protecting the system from excessive or unauthorized deposit attempts.</p>
     *
     * <p>Execution flow for each generated {@link MoneyDepositRequest}:</p>
     * <ul>
     *   <li>Invokes {@link #init()} to reset all mocked collaborators, guaranteeing an
     *       isolated environment for the property trial.</li>
     *   <li>Stubs {@link LedgerService#recordEntry(CreateLedgerEntryRequest)} to return an empty
     *       {@link LedgerEntry LedgerEntry}.</li>
     *   <li>Calls {@link AdminWalletService#depositFunds(String, UUID, MoneyDepositRequest)}
     *       with the admin credentials and the supplied {@code request}.</li>
     *   <li>Verifies that {@link RateLimiterService#checkLimit(String, String, long)} is
     *       invoked exactly once with {@code ADMIN_ID.toString()}, the key {@code "admin_deposit"},
     *       and the quota {@code 5L}.</li>
     * </ul>
     *
     * @param request a {@link MoneyDepositRequest} instance produced by the
     *                {@code validDepositRequests()} provider. It contains a valid
     *                {@code amount}, {@code currency} and an optional {@code description}
     *                that simulate realistic deposit payloads.
     *
     * @see AdminWalletController
     * @see AdminWalletService
     * @see LedgerService
     * @see RateLimiterService
     */
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

    /**
     * <p>Verifies that a {@link MoneyDepositRequest} with a {@code null} description is
     * processed by {@link AdminWalletService#depositFunds(String, UUID, MoneyDepositRequest)}
     * using the default audit description <strong>"ADMIN_DEPOSIT: Manual Top‑Up"</strong>.</p>
     *
     * <p>The test follows these steps:</p>
     * <ul>
     *   <li>Calls {@link #init()} to reset all mocked collaborators, ensuring an isolated
     *   environment for each trial.</li>
     *   <li>Creates a {@link MoneyDepositRequest} with the supplied {@code amount},
     *   {@code currency} and a {@code null} description.</li>
     *   <li>Stubs {@link LedgerService#recordEntry(CreateLedgerEntryRequest)} to return an
     *   empty {@link LedgerEntry LedgerEntry}.</li>
     *   <li>Invokes {@link AdminWalletService#depositFunds(String, UUID, MoneyDepositRequest)}
     *   and captures the {@link CreateLedgerEntryRequest} passed to {@link LedgerService#recordEntry}.</li>
     *   <li>Asserts that the captured request’s {@code description} equals
     *   {@code "ADMIN_DEPOSIT: Manual Top-Up"}.</li>
     * </ul>
     *
     * <p>This ensures robustness of the admin deposit flow by guaranteeing that missing
     * descriptions do not result in ambiguous ledger entries and that the system
     * consistently records a meaningful audit trail.</p>
     *
     * @param amount   the monetary amount to deposit; generated within the range
     *                 {@code 0.01}–{@code 1_000_000.00} by the property‑based test.
     * @param currency the ISO‑4217 currency code associated with {@code amount}.
     *
     * @see AdminWalletController
     * @see AdminWalletService
     * @see LedgerService
     */
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