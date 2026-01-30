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
}