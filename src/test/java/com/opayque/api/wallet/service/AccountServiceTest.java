package com.opayque.api.wallet.service;

import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.repository.AccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

}