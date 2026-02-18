package com.opayque.api.wallet.service;

import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.AccountStatus;
import com.opayque.api.wallet.repository.AccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/// Multi-Currency Account Management - Service Logic Verification.
///
/// This suite executes isolated unit tests for the [AccountService] using Mockito
/// to simulate infrastructure dependencies. It validates the core business rules
/// for digital wallet provisioning, including identity lookups, currency
/// constraints, and jurisdictional IBAN generation logic.
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private UserRepository userRepository;
    @Mock private IbanGenerator ibanGenerator;

    @InjectMocks
    private AccountService accountService;

    /// Scenario: Successful Wallet Provisioning.
    ///
    /// Validates that providing a valid User ID and ISO 4217 currency code results
    /// in a correctly mapped [Account] entity with a generated IBAN.
    ///
    /// Verification: Ensures the entity is correctly associated with the user
    /// and persists through the [AccountRepository].
    @Test
    @DisplayName("Create: Should successfully create a new wallet with IBAN")
    void shouldCreateAccountSuccessfully() {
        // Arrange: Establish mock identity and generation results.
        UUID userId = UUID.randomUUID();
        String currency = "USD";
        String mockIban = "US89370400440532013000";
        User mockUser = User.builder().id(userId).email("test@opayque.com").build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        when(accountRepository.existsByUserIdAndCurrencyCode(userId, currency)).thenReturn(false);
        when(ibanGenerator.generate(currency)).thenReturn(mockIban);

        // Intercepts the save call to return the entity for assertion.
        when(accountRepository.save(any(Account.class))).thenAnswer(i -> i.getArguments()[0]);

        // Act: Execute wallet creation.
        Account created = accountService.createAccount(userId, currency);

        // Assert: Audit the constructed entity state.
        assertThat(created.getCurrencyCode()).isEqualTo("USD");
        assertThat(created.getIban()).isEqualTo(mockIban);
        assertThat(created.getUser()).isEqualTo(mockUser);

        verify(accountRepository).save(any(Account.class));
    }

    /// Scenario: Identity Resolution Failure (UUID).
    ///
    /// Ensures the service rejects provisioning requests if the provided User UUID
    /// does not exist within the primary identity ledger.
    @Test
    @DisplayName("Create: Should throw exception if User does not exist")
    void shouldFailIfUserNotFound() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.createAccount(userId, "EUR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User not found");

        verify(accountRepository, never()).save(any());
    }

    /// Scenario: Jurisdictional 1:1 Rule Enforcement.
    ///
    /// Validates that a user cannot possess duplicate wallets for the same
    /// ISO 4217 currency, satisfying the SAS (Single Active Session) policy
    /// for wallet assets.
    @Test
    @DisplayName("Create: Should fail if User already has a wallet in that currency (Application Check)")
    void shouldFailIfAccountExists_AppCheck() {
        UUID userId = UUID.randomUUID();
        User mockUser = User.builder().id(userId).build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        when(accountRepository.existsByUserIdAndCurrencyCode(userId, "GBP")).thenReturn(true);

        assertThatThrownBy(() -> accountService.createAccount(userId, "GBP"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Wallet already exists");

        verify(accountRepository, never()).save(any());
    }

    /// Scenario: Invalid Currency Guardrail.
    ///
    /// Confirms that the service propagates exceptions from the [IbanGenerator]
    /// when an unsupported or malformed currency code is provided.
    @Test
    @DisplayName("Create: Should fail if Currency Code is invalid (ISO 4217 Check)")
    void shouldFailOnInvalidCurrency() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(new User()));

        // Simulate a validation failure within the generation engine.
        when(ibanGenerator.generate("LOL")).thenThrow(new IllegalArgumentException("Invalid currency code"));

        assertThatThrownBy(() -> accountService.createAccount(userId, "LOL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid currency code");

        verify(accountRepository, never()).save(any());
    }

  /// Scenario: Regulatory Sanction Enforcement – Unsupported Currency (USD).
  ///
  /// Validates that wallet provisioning is rejected when the requested currency
  /// is not on the platform’s approved ISO-4217 whitelist. USD is explicitly
  /// blocked due to IBAN territory restrictions and AML policy alignment with the
  /// Central Bank of Nigeria (CBN) sanctions list. This test ensures the service
  /// layer propagates the generator’s exception, preventing any persistence
  /// of non-compliant financial identifiers.
  @Test
  @DisplayName("Security Check: Should Block Unsupported Currencies (e.g., USD)")
  void createAccount_ShouldFailForUSD() {
        // Given
        String userEmail = "test@opayque.com";
        // Mock the User lookup to succeed
        when(userRepository.findByEmail(userEmail)).thenReturn(Optional.of(new User()));

        // FIX: We must instruct the Mock to behave like the Real Class.
        // In the real IbanGeneratorImpl, "USD" throws an exception.
        // Since this is a Unit Test, we stub that failure here to ensure AccountService propagates it.
        when(ibanGenerator.generate("USD"))
                .thenThrow(new IllegalArgumentException("Territory not supported for IBAN generation: USD"));

        // When/Then
        assertThatThrownBy(() -> accountService.createAccount(userEmail, "USD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Territory not supported");

        // Assert: Ensure we strictly blocked the database write
        verify(accountRepository, never()).save(any());
    }

    /// Scenario: Identity Resolution Failure (Email).
    ///
    /// Validates that requests utilizing a non-existent email address are
    /// rejected before any financial identifiers are generated.
    @Test
    @DisplayName("Create: Should fail and throw exception when User Email is not found in DB")
    void shouldFailWhenUserNotFound() {
        String unknownEmail = "ghost@opayque.com";
        when(userRepository.findByEmail(unknownEmail)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.createAccount(unknownEmail, "USD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("User not found");

        verify(accountRepository, never()).save(any());
    }

    /// Scenario: Portfolio Retrieval Failure (User Not Found).
    ///
    /// Validates that the "Dashboard" fetch fails gracefully if the user identity
    /// cannot be resolved from the provided email.
    @Test
    @DisplayName("GetAccounts: Should fail with 'User not found' when email does not exist")
    void shouldFailWhenFetchingPortfolioForUnknownUser() {
        // Arrange
        String unknownEmail = "ghost@opayque.com";
        when(userRepository.findByEmail(unknownEmail)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> accountService.getAccountsForUser(unknownEmail))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("User not found");

        // Verify we never attempted to fetch the portfolio since identity failed
        verify(accountRepository, never()).findAllByUserId(any());
    }

    /// Scenario: Single Account Lookup Failure (ID Not Found).
    ///
    /// Ensures that retrieving a specific wallet by ID throws the correct exception
    /// if the ID does not exist in the persistence layer.
    @Test
    @DisplayName("GetAccountById: Should fail with 'Account not found' when ID does not exist")
    void shouldFailWhenFetchingUnknownAccountById() {
        // Arrange
        UUID unknownAccountId = UUID.randomUUID();
        when(accountRepository.findById(unknownAccountId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> accountService.getAccountById(unknownAccountId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Account not found");

        // Explicitly verifies the lookup attempt occurred
        verify(accountRepository).findById(unknownAccountId);
    }

    /// Scenario: Successful Wallet ID Resolution.
    ///
    /// Validates that the service correctly maps a (User Email + Currency) pair
    /// to a specific Wallet UUID. This is the "Happy Path" for the transfer service.
    @Test
    @DisplayName("GetIdByEmail: Should return Wallet UUID when User and Currency match")
    void shouldResolveWalletIdSuccessfully() {
        // Arrange
        String email = "sender@opayque.com";
        String currency = "USD";
        UUID userId = UUID.randomUUID();
        UUID expectedWalletId = UUID.randomUUID();

        User mockUser = User.builder().id(userId).email(email).build();

        // Mock the wallet (Ensure currency matches!)
        Account mockWallet = Account.builder()
                .id(expectedWalletId)
                .user(mockUser)
                .currencyCode("USD")
                .build();

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(mockUser));
        when(accountRepository.findAllByUserId(userId)).thenReturn(List.of(mockWallet));

        // Act
        UUID actualId = accountService.getAccountIdByEmail(email, currency);

        // Assert
        assertThat(actualId).isEqualTo(expectedWalletId);
    }

    /// Scenario: Identity Resolution Failure.
    ///
    /// Validates that the lookup fails fast if the email is not found,
    /// preventing unnecessary DB queries for wallets.
    @Test
    @DisplayName("GetIdByEmail: Should fail fast if User Email does not exist")
    void shouldFailIdLookupWhenUserNotFound() {
        // Arrange
        String unknownEmail = "ghost@opayque.com";
        when(userRepository.findByEmail(unknownEmail)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> accountService.getAccountIdByEmail(unknownEmail, "USD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("User not found");

        // Verify we never even looked for wallets
        verify(accountRepository, never()).findAllByUserId(any());
    }

    /// Scenario: Missing Currency Wallet.
    ///
    /// Validates the business rule: A user might exist, but if they don't possess
    /// a wallet in the requested currency, we must reject the operation.
    @Test
    @DisplayName("GetIdByEmail: Should fail if User has no wallet in the requested currency")
    void shouldFailIfUserHasNoWalletForCurrency() {
        // Arrange
        String email = "euro_user@opayque.com";
        String requestedCurrency = "USD"; // User wants USD...
        UUID userId = UUID.randomUUID();

        User mockUser = User.builder().id(userId).email(email).build();

        // ...but User ONLY has a EUR wallet
        Account euroWallet = Account.builder()
                .id(UUID.randomUUID())
                .user(mockUser)
                .currencyCode("EUR")
                .build();

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(mockUser));
        // Return portfolio containing only EUR
        when(accountRepository.findAllByUserId(userId)).thenReturn(List.of(euroWallet));

        // Act & Assert
        assertThatThrownBy(() -> accountService.getAccountIdByEmail(email, requestedCurrency))
                .isInstanceOf(IllegalArgumentException.class)
                // Match the exact error message format from your service
                .hasMessage("No " + requestedCurrency + " wallet found for user");
    }

    // =========================================================================
    // EPIC 5: LIFECYCLE MANAGEMENT TESTS
    // =========================================================================

    @Test
    @DisplayName("UpdateStatus: Should throw exception when account does not exist")
    void updateStatus_AccountNotFound() {
        // Given
        UUID randomId = UUID.randomUUID();
        when(accountRepository.findById(randomId)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> accountService.updateAccountStatus(randomId, AccountStatus.FROZEN, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Account not found");

        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("UpdateStatus: Should throw exception for illegal state transition (CLOSED -> ACTIVE)")
    void updateStatus_IllegalTransition() {
        // Given
        UUID accountId = UUID.randomUUID();
        Account closedAccount = Account.builder()
                .id(accountId)
                .status(AccountStatus.CLOSED) // Terminal state
                .build();

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(closedAccount));

        // When & Then
        assertThatThrownBy(() -> accountService.updateAccountStatus(accountId, AccountStatus.ACTIVE, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot transition");

        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("Security: Non-Admin CANNOT freeze an account")
    void updateStatus_UserCannotFreeze() {
        // Given
        UUID accountId = UUID.randomUUID();
        Account activeAccount = Account.builder().id(accountId).status(AccountStatus.ACTIVE).build();

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount));

        // When & Then
        assertThatThrownBy(() -> accountService.updateAccountStatus(accountId, AccountStatus.FROZEN, false)) // isAdmin = false
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only Administrators can freeze accounts.");

        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("Security: Non-Admin CANNOT unfreeze a frozen account")
    void updateStatus_UserCannotUnfreeze() {
        // Given
        UUID accountId = UUID.randomUUID();
        Account frozenAccount = Account.builder().id(accountId).status(AccountStatus.FROZEN).build();

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(frozenAccount));

        // When & Then
        assertThatThrownBy(() -> accountService.updateAccountStatus(accountId, AccountStatus.ACTIVE, false)) // isAdmin = false
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only Administrators can unfreeze accounts.");

        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("Admin: Should successfully FREEZE an active account")
    void updateStatus_AdminCanFreeze() {
        // Given
        UUID accountId = UUID.randomUUID();
        Account activeAccount = Account.builder().id(accountId).status(AccountStatus.ACTIVE).build();

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        Account updated = accountService.updateAccountStatus(accountId, AccountStatus.FROZEN, true); // isAdmin = true

        // Then
        assertThat(updated.getStatus()).isEqualTo(AccountStatus.FROZEN);
        verify(accountRepository).save(activeAccount);
    }

    @Test
    @DisplayName("Admin: Should successfully UNFREEZE a frozen account")
    void updateStatus_AdminCanUnfreeze() {
        // Given
        UUID accountId = UUID.randomUUID();
        Account frozenAccount = Account.builder().id(accountId).status(AccountStatus.FROZEN).build();

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(frozenAccount));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        Account updated = accountService.updateAccountStatus(accountId, AccountStatus.ACTIVE, true); // isAdmin = true

        // Then
        assertThat(updated.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(accountRepository).save(frozenAccount);
    }

    @Test
    @DisplayName("User: Should successfully CLOSE their own account (Soft Delete)")
    void updateStatus_UserCanCloseAccount() {
        // Given
        UUID accountId = UUID.randomUUID();
        Account activeAccount = Account.builder().id(accountId).status(AccountStatus.ACTIVE).build();

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        // isAdmin = false (User action), Target = CLOSED
        Account updated = accountService.updateAccountStatus(accountId, AccountStatus.CLOSED, false);

        // Then
        assertThat(updated.getStatus()).isEqualTo(AccountStatus.CLOSED);
        verify(accountRepository).save(activeAccount);
    }

    @Test
    @DisplayName("UpdateStatus: Should throw AccessDenied when non-admin tries to unfreeze (ACTIVE) a FROZEN account")
    void updateAccountStatus_WhenNonAdminUnfreezes_ShouldThrowAccessDeniedException() {
        // Arrange
        UUID accountId = UUID.randomUUID();
        // Step 1: Account is currently FROZEN
        Account frozenAccount = Account.builder()
                .id(accountId)
                .status(AccountStatus.FROZEN)
                .build();

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(frozenAccount));

        // Act & Assert
        // Step 2: Attempting to set to ACTIVE with isAdmin = false
        assertThatThrownBy(() ->
                accountService.updateAccountStatus(accountId, AccountStatus.ACTIVE, false)
        )
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only Administrators can unfreeze accounts.");

        // Verify no save was attempted
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("UpdateStatus: Should succeed when ADMIN unfreezes (ACTIVE) a FROZEN account")
    void updateAccountStatus_WhenAdminUnfreezes_ShouldSucceed() {
        // Arrange
        UUID accountId = UUID.randomUUID();
        Account frozenAccount = Account.builder()
                .id(accountId)
                .status(AccountStatus.FROZEN)
                .build();

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(frozenAccount));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        // Step 2: Attempting to set to ACTIVE with isAdmin = true
        Account result = accountService.updateAccountStatus(accountId, AccountStatus.ACTIVE, true);

        // Assert
        assertThat(result.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(accountRepository).save(frozenAccount);
    }


    @Test
    @DisplayName("Rule B Coverage: Condition 1 False - Current status is NOT Frozen")
    void updateAccountStatus_RuleB_Branch1_NotFrozen() {
        UUID id = UUID.randomUUID();
        // Setup: Status is ACTIVE (Not FROZEN)
        Account account = Account.builder().id(id).status(AccountStatus.ACTIVE).build();

        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenReturn(account);

        // Act: Transition to CLOSED (Condition 1 of Rule B fails immediately)
        accountService.updateAccountStatus(id, AccountStatus.CLOSED, false);

        // Verification: The line was executed, but the 'if' block was skipped because first check failed
        verify(accountRepository).save(any());
    }

    @Test
    @DisplayName("Rule B Coverage: Condition 2 False - Current is Frozen but target is NOT Active")
    void updateAccountStatus_RuleB_Branch2_NotTransitioningToActive() {
        UUID id = UUID.randomUUID();
        // Setup: Status is FROZEN
        Account account = Account.builder().id(id).status(AccountStatus.FROZEN).build();

        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenReturn(account);

        // Act: Transition to CLOSED (Condition 2 of Rule B fails: target is not ACTIVE)
        accountService.updateAccountStatus(id, AccountStatus.CLOSED, true);

        verify(accountRepository).save(any());
    }

    @Test
    @DisplayName("Rule B Coverage: Condition 3 False - Admin is performing the unfreeze")
    void updateAccountStatus_RuleB_Branch3_AdminBypass() {
        UUID id = UUID.randomUUID();
        Account account = Account.builder().id(id).status(AccountStatus.FROZEN).build();

        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenReturn(account);

        // Act: Transition to ACTIVE with isAdmin = true (Condition 3 of Rule B fails: !isAdmin is false)
        accountService.updateAccountStatus(id, AccountStatus.ACTIVE, true);

        verify(accountRepository).save(any());
    }

    @Test
    @DisplayName("Rule B Coverage: Full True - Non-admin attempts to unfreeze (Exception Case)")
    void updateAccountStatus_RuleB_Branch4_TriggerException() {
        UUID id = UUID.randomUUID();
        Account account = Account.builder().id(id).status(AccountStatus.FROZEN).build();

        when(accountRepository.findById(id)).thenReturn(Optional.of(account));

        // Act & Assert: All conditions met, exception thrown
        assertThatThrownBy(() ->
                accountService.updateAccountStatus(id, AccountStatus.ACTIVE, false)
        ).isInstanceOf(AccessDeniedException.class);
    }
}