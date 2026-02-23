package com.opayque.api.admin.service;

import com.opayque.api.admin.controller.AdminWalletController;
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
import org.assertj.core.api.ThrowableAssert;
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

/**
 * <p>Integration test suite for {@link AdminWalletService} that validates the administrative
 * operations available to a privileged user (the “god‑mode” admin). The tests exercise two core
 * functional areas:</p>
 *
 * <ul>
 *   <li><strong>Account status updates</strong> – ensures that an admin can transition an
 *       {@link Account} to a new {@link AccountStatus} while respecting access control and
 *       rate‑limiting policies.</li>
 *   <li><strong>Fund deposits</strong> – verifies that an admin can create a credit
 *       {@link LedgerEntry} (the “Fiat God Mode” operation) with correct audit‑trail metadata,
 *       default descriptions, and strict rate‑limit enforcement.</li>
 * </ul>
 *
 * <p>Each test method follows the Arrange‑Act‑Assert pattern and makes extensive use of Mockito
 * to isolate {@link AdminWalletService} from its collaborators:</p>
 *
 * <ul>
 *   <li>{@link AccountService} – performs the actual account status mutation.</li>
 *   <li>{@link RateLimiterService} – governs the number of admin actions per minute.</li>
 *   <li>{@link UserRepository} – resolves the admin identity from the supplied email address.</li>
 *   <li>{@link LedgerService} – records financial ledger entries for deposit operations.</li>
 * </ul>
 *
 * <p>The suite also validates that no side‑effects occur when pre‑conditions fail (e.g., admin
 * not found or rate limit exceeded) by using {@code verifyNoInteractions(...)} and
 * {@code verifyNoMoreInteractions(...)}.</p>
 *
 * <p>Purpose:</p>
 *
 * <ul>
 *   <li>Guarantee that only a verified admin (identified by {@code ADMIN_EMAIL}) can invoke
 *       privileged actions.</li>
 *   <li>Confirm that governance constraints ({@code admin_action} and {@code admin_deposit}
 *       quotas) are enforced before business logic executes.</li>
 *   <li>Ensure auditability by checking that ledger entries contain a prefixed description such
 *       as {@code "ADMIN_DEPOSIT: …"} or a sensible default when the description is omitted.</li>
 * </ul>
 *
 * @author Madavan Babu
 * @since 2026
 *
 * @see AdminWalletService
 * @see AccountService
 * @see RateLimiterService
 * @see UserRepository
 * @see LedgerService
 */
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

    /**
     * <p>Validates the happy‑path execution of {@link AdminWalletService#updateAccountStatus(String, UUID, AccountStatus)}
     * when a legitimate admin user is supplied and the rate‑limit check succeeds.</p>
     *
     * <p>The test follows the classic Arrange‑Act‑Assert pattern:</p>
     *
     * <ul>
     *   <li><strong>Arrange:</strong> Constructs an {@link Account} instance with the target
     *       {@link UUID} {@code TARGET_ACCOUNT_ID} and a new {@link AccountStatus}
     *       of {@link AccountStatus#FROZEN}. Mocks are prepared so that
     *       {@link UserRepository#findByEmail(String)} returns a valid admin user and
     *       {@link AccountService#updateAccountStatus(UUID, AccountStatus, boolean)}
     *       returns the updated account.</li>
     *   <li><strong>Act:</strong> Invokes {@code adminWalletService.updateAccountStatus}
     *       with {@code ADMIN_EMAIL}, {@code TARGET_ACCOUNT_ID} and the new status.</li>
     *   <li><strong>Assert:</strong> Confirms that the resulting {@link Account}'s status
     *       equals {@link AccountStatus#FROZEN}.</li>
     *   <li><strong>Verify Governance:</strong> Ensures the {@link RateLimiterService}
     *       performed a limit check for the admin identifier with the operation key
     *       {@code "admin_action"} and a quota of {@code 10}.</li>
     *   <li><strong>Verify Execution:</strong> Confirms the {@link AccountService}
     *       was called to perform the status transition with the {@code true}
     *       flag indicating an admin‑initiated operation.</li>
     * </ul>
     *
     * @see AdminWalletService
     * @see AccountService
     * @see RateLimiterService
     * @see UserRepository
     */
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

    /**
     * <p>Verifies that {@link AdminWalletService#updateAccountStatus(String, UUID, AccountStatus)} aborts
     * the operation when the administrator cannot be located.</p>
     *
     * <p>Test flow:</p>
     * <ul>
     *   <li><strong>Arrange:</strong> The {@link UserRepository} mock returns {@link Optional#empty()}
     *       for the admin identifier {@code ADMIN_EMAIL}.</li>
     *   <li><strong>Act &amp; Assert:</strong> Invoking {@code adminWalletService.updateAccountStatus}
     *       with {@code ADMIN_EMAIL}, {@code TARGET_ACCOUNT_ID} and {@code AccountStatus.FROZEN}
     *       results in an {@link AccessDeniedException} whose
     *       message contains “Admin context invalid”.</li>
     *   <li><strong>Verify:</strong> No interactions occur with {@link RateLimiterService} or
     *       {@link AccountService}, confirming that the method short‑circuits before any rate‑limit
     *       checks or domain state changes are performed.</li>
     * </ul>
     *
     * @see AdminWalletService
     * @see AdminWalletController
     * @see AccountService
     * @see RateLimiterService
     * @see UserRepository
     */
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

    /**
     * <p>Verifies that {@link AdminWalletService#updateAccountStatus(String, UUID, AccountStatus)}
     * propagates a {@link RateLimitExceededException} when the {@link RateLimiterService} rejects the
     * request due to exceeded quota.</p>
     *
     * <p>The test follows the classic Arrange‑Act‑Assert pattern:</p>
     * <ul>
     *   <li><strong>Arrange:</strong> {@link UserRepository} returns a valid admin for
     *       {@code ADMIN_EMAIL}; {@link RateLimiterService} is stubbed to throw a
     *       {@link RateLimitExceededException} for any limit check.</li>
     *   <li><strong>Act &amp; Assert:</strong> Invocation of
     *       {@code adminWalletService.updateAccountStatus(ADMIN_EMAIL, TARGET_ACCOUNT_ID, AccountStatus.FROZEN)}
     *       is wrapped in {@link org.assertj.core.api.Assertions#assertThatThrownBy(ThrowableAssert.ThrowingCallable)} (org.assertj.core.api.ThrowingCallable)}
     *       and asserted to be an instance of {@link RateLimitExceededException}.</li>
     *   <li><strong>Verify:</strong> {@link AccountService} receives no interactions, confirming that
     *       the core business logic is short‑circuited when rate limiting fails.</li>
     * </ul>
     *
     * @see AdminWalletService
     * @see RateLimiterService
     * @see AccountService
     * @see UserRepository
     * @see AdminWalletController
     */
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

    /**
     * <p>Validates the happy‑path execution of {@link AdminWalletService#depositFunds(String, UUID, MoneyDepositRequest)}
     * when an administrator initiates a deposit and supplies an explicit description.</p>
     *
     * <p>The test follows the classic Arrange‑Act‑Assert pattern:</p>
     * <ul>
     *   <li><strong>Arrange:</strong> A {@link MoneyDepositRequest} is constructed with amount
     *       {@code new BigDecimal("1000.00")}, currency {@code "EUR"} and description {@code "Stimulus Package"}.
     *       Mocks are configured so that {@link UserRepository#findByEmail(String)} returns a valid admin user
     *       and {@link LedgerService#recordEntry(CreateLedgerEntryRequest)} returns a pre‑built {@link LedgerEntry}
     *       representing a {@link TransactionType#CREDIT} entry.</li>
     *   <li><strong>Act:</strong> {@code adminWalletService.depositFunds(ADMIN_EMAIL, TARGET_ACCOUNT_ID, request)} is invoked.</li>
     *   <li><strong>Assert:</strong> Verifies that the resulting {@link LedgerEntry} is not {@code null} and that the
     *       captured {@link CreateLedgerEntryRequest} contains the expected {@code accountId},
     *       {@code amount}, {@code currency}, transaction type {@link TransactionType#CREDIT}, and a formatted
     *       description {@code "ADMIN_DEPOSIT: Stimulus Package"}.</li>
     *   <li><strong>Governance Verification:</strong> Confirms that {@link RateLimiterService#checkLimit(String, String, long)}
     *       is called with the administrator identifier, operation key {@code "admin_deposit"}, and quota {@code 5}.</li>
     * </ul>
     *
     * @see AdminWalletService
     * @see AdminWalletController
     * @see LedgerService
     * @see RateLimiterService
     * @see UserRepository
     */
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

    /**
     * Verifies that a deposit operation uses the default audit description when the
     * {@link MoneyDepositRequest} does not provide one.
     *
     * <p>This test follows the happy‑path scenario where all input values are valid:
     * a positive amount, a supported currency, and a non‑null target account. The
     * only missing piece is the optional {@code description} field, which is
     * {@code null} to simulate a caller omission.</p>
     *
     * <p>The method under test, {@link AdminWalletService#depositFunds(String, UUID, MoneyDepositRequest)},
     * should fall back to the hard‑coded default description
     * {@code "ADMIN_DEPOSIT: Manual Top‑Up"} before delegating to the {@link LedgerService}
     * to record the transaction. The test captures the {@link CreateLedgerEntryRequest}
     * passed to {@link LedgerService#recordEntry(CreateLedgerEntryRequest)} and asserts
     * that its {@code description} matches the expected default.</p>
     *
     * @see AdminWalletController
     * @see AdminWalletService
     * @see LedgerService
     * @see UserRepository
     * @see MoneyDepositRequest
     * @see CreateLedgerEntryRequest
     */
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

    /**
     * <p>Validates that {@code AdminWalletService.depositFunds(...)} throws an
     * {@code AccessDeniedException} when the administrator identified by {@code ADMIN_EMAIL}
     * cannot be found in the {@code UserRepository}.</p>
     *
     * <p>The test creates a {@link MoneyDepositRequest} representing a deposit of {@code 100.00}
     * USD, configures {@link UserRepository} to return {@link Optional#empty()} for the admin email,
     * and asserts that invoking {@code adminWalletService.depositFunds(ADMIN_EMAIL, TARGET_ACCOUNT_ID, request)}
     * results in an {@code AccessDeniedException}. It also verifies that no interactions occur with the
     * {@link LedgerService}, ensuring the ledger remains untouched.</p>
     *
     * @see AdminWalletService}
     * @see UserRepository
     * @see LedgerService
     */
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

    /**
     * Verifies that {@link AdminWalletService#depositFunds(String, UUID, MoneyDepositRequest)} enforces
     * the strict rate‑limit policy of five deposit operations per minute for admin users.
     * <p>
     * The test arranges a {@link MoneyDepositRequest} for a USD {@code 100.00} deposit,
     * mocks {@link UserRepository} to return the admin user, and configures
     * {@link RateLimiterService} to throw a {@link RateLimitExceededException} when the
     * limit check is performed.
     * <p>
     * It asserts that the {@link RateLimitExceededException} is propagated and that no
     * interaction occurs with {@link LedgerService}, guaranteeing that no ledger entry
     * is created when the rate limit is exceeded.
     *
     * @see AdminWalletController
     * @see AdminWalletService
     * @see RateLimiterService
     */
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