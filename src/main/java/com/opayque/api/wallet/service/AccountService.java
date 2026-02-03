package com.opayque.api.wallet.service;

import com.opayque.api.wallet.controller.WalletController;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/// Multi-Currency Account Management - Wallet Provisioning Service.
///
/// This service orchestrates the lifecycle of digital wallets within the oPayque ecosystem.
/// It acts as the primary orchestrator for user identity verification,
/// bank-grade IBAN generation, and the enforcement of jurisdictional 1:1
/// currency constraints.
///
/// Architecture: Decouples the Controller from the Persistence layer by handling
/// identity lookups internally, ensuring that the public API remains stateless
/// and opaque.
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final IbanGenerator ibanGenerator;

    /// Provisions a wallet for a user identified by their primary email address.
    ///
    /// This is the standard entry point for requests originating from the [WalletController].
    /// It verifies the existence of the identity before delegating to the
    /// core creation logic.
    ///
    /// @param userEmail    The verified email address from the JWT security context.
    /// @param currencyCode The ISO 4217 currency code for the target wallet territory.
    /// @return The persisted [Account] entity representing the new wallet.
    /// @throws IllegalArgumentException If the user identity cannot be resolved.
    @Transactional
    public Account createAccount(String userEmail, String currencyCode) {
        log.info("Request to create wallet. UserEmail: [{}], Currency: [{}]", userEmail, currencyCode);

        // Identity Verification: Ensure the requester exists in the primary user ledger.
        User owner = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> {
                    log.error("Wallet creation failed. User Email [{}] not found.", userEmail);
                    return new IllegalArgumentException("User not found");
                });

        return createAccountForUser(owner, currencyCode);
    }

    /// Provisions a wallet using a direct Universally Unique Identifier (UUID).
    ///
    /// Designed for administrative operations, internal scheduled tasks, or
    /// high-privileged service calls where the user's primary email is unavailable
    ///.
    ///
    /// @param userId       The internal UUID of the target user.
    /// @param currencyCode The ISO 4217 currency code for the target wallet territory.
    /// @return The persisted [Account] entity representing the new wallet.
    /// @throws IllegalArgumentException If the user identity cannot be resolved by ID.
    @Transactional
    public Account createAccount(UUID userId, String currencyCode) {
        log.info("Request to create wallet. UserID: [{}], Currency: [{}]", userId, currencyCode);

        User owner = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.error("Wallet creation failed. User ID [{}] not found.", userId);
                    return new IllegalArgumentException("User not found with ID: " + userId);
                });

        return createAccountForUser(owner, currencyCode);
    }

    /// Core Business Engine: Implements jurisdictional and financial guardrails.
    ///
    /// Enforces the system-wide 1:1 SAS (Single Active Session) rule for wallets:
    /// A user identity may possess exactly one active wallet per ISO 4217 currency.
    ///
    /// The process flow involves:
    /// 1. Uniqueness check via [AccountRepository].
    /// 2. ISO 13616 compliant IBAN generation.
    /// 3. ACID-compliant persistence within a transactional boundary.
    private Account createAccountForUser(User owner, String currencyCode) {
        // 1. Enforce 1:1 Rule: Prevents duplicate wallet sprawl per user per territory.
        if (accountRepository.existsByUserIdAndCurrencyCode(owner.getId(), currencyCode)) {
            log.warn("Duplicate wallet attempt. User: [{}], Currency: [{}]", owner.getId(), currencyCode);
            throw new IllegalStateException("Wallet already exists for currency: " + currencyCode);
        }

        // 2. Generate Bank-Grade IBAN utilizing the configured jurisdictional generator.
        String iban = ibanGenerator.generate(currencyCode);
        log.debug("Generated IBAN for new wallet: [{}]", iban);

        // 3. Entity Construction: Maps the relationship between the Identity and the new Wallet.
        Account newAccount = Account.builder()
                .user(owner)
                .currencyCode(currencyCode)
                .iban(iban)
                .build();

        // 4. Persistence: Commits the new account to the PostgreSQL ledger.
        Account savedAccount = accountRepository.save(newAccount);

        // Security Logging: Only partial segments of the IBAN are logged to maintain
        // PII compliance and prevent account enumeration.
        log.info("Wallet successfully created. AccountID: [{}], IBAN: [{}...{}]",
                savedAccount.getId(),
                iban.substring(0, 4),
                iban.substring(iban.length() - 4));

        return savedAccount;
    }

    /// Retrieves all active wallets for a specific user.
    ///
    /// This method serves the "Dashboard" view. It resolves the user's identity
    /// via their email (from the Security Context) and then fetches the complete
    /// list of their multi-currency accounts.
    ///
    /// @param userEmail The verified email address from the JWT.
    /// @return A list of [Account] entities owned by the user.
    /// @throws IllegalArgumentException If the user identity cannot be found.
    @Transactional(readOnly = true)
    public List<Account> getAccountsForUser(String userEmail) {
        log.debug("Fetching account portfolio for user: [{}]", userEmail);

        // 1. Resolve Identity (Ensures we don't return empty lists for non-existent users)
        User owner = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> {
                    log.error("Portfolio fetch failed. User Email [{}] not found.", userEmail);
                    return new IllegalArgumentException("User not found");
                });

        // 2. Fetch Portfolio
        List<Account> portfolio = accountRepository.findAllByUserId(owner.getId());

        log.debug("Found [{}] wallets for user [{}]", portfolio.size(), owner.getId());
        return portfolio;
    }



    /// Retrieves an [Account] by its unique identifier.
    /// This method fetches an account instance based on the provided UUID. If no account
    /// is found, an [IllegalArgumentException] is thrown to indicate the absence of
    /// the requested account.
    ///
    /// @param accountId The unique identifier of the account to retrieve.
    /// @return The [Account] entity corresponding to the given identifier.
    /// @throws IllegalArgumentException If the account with the specified ID is not found.
    @Transactional(readOnly = true)
    public Account getAccountById(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> {
                    log.error("Account lookup failed. ID [{}] not found.", accountId);
                    // This maps to 404 Not Found in the Global Exception Handler
                    return new IllegalArgumentException("Account not found");
                });
    }
}