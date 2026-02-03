package com.opayque.api.wallet.controller;

import com.opayque.api.wallet.dto.AccountResponse;
import com.opayque.api.wallet.dto.CreateAccountRequest;
import com.opayque.api.wallet.dto.WalletSummary;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.service.AccountService;
import com.opayque.api.wallet.service.LedgerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/// Multi-Currency Account Management - Wallet API Layer.
///
/// This controller provides the entry point for the Multi-Currency Wallet System.
/// It facilitates the creation and management of separate digital wallets for diverse
/// ISO 4217 currencies (e.g., USD, EUR, GBP).
///
/// Architecture: Adheres to a "Plug-and-Play" REST standard designed for modern React
/// or Flutter frontends. It delegates business logic
/// to the [AccountService] to maintain a clean separation of concerns.
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Slf4j
public class WalletController {

    private final AccountService accountService;
    private final LedgerService ledgerService;

    /// Provisions a new multi-currency account for the authenticated user identity.
    ///
    /// This endpoint extracts the user's primary identity from the [Authentication]
    /// context and creates a sub-wallet restricted to a specific currency code.
    ///
    /// Validation: Relies on the GlobalExceptionHandler to map business violations,
    /// such as invalid currency codes or duplicate account creation, into a
    /// consistent JSON error format.
    ///
    /// @param request The validated account creation payload containing the target currency.
    /// @param authentication The security context containing the user's verified identity.
    /// @return A 201 Created response containing the [AccountResponse] DTO.
    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(
            @Valid @RequestBody CreateAccountRequest request,
            Authentication authentication
    ) {
        String userEmail = authentication.getName();
        log.info("API: Create Account Request. User: [{}], Currency: [{}]", userEmail, request.getCurrencyCode());

        // Business logic delegation for wallet provisioning within the identity ledger.
        Account createdAccount = accountService.createAccount(userEmail, request.getCurrencyCode());

        // Returns the standardized response format for easy UI handling by the client app.
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(AccountResponse.fromEntity(createdAccount));
    }

    /// Dashboard View: Retrieves all wallets for the user + their real-time balances.
    ///
    /// This is the "Heavy Lifter" for the frontend. It fetches the user's portfolio
    /// from the AccountService and then loops through each wallet to calculate
    /// the live balance from the LedgerService.
    ///
    /// @param authentication The verified security context.
    /// @return A summary list of wallets with calculated balances.
    @GetMapping
    public ResponseEntity<List<WalletSummary>> getMyWallets(Authentication authentication) {
        String userEmail = authentication.getName();

        // 1. Fetch the list of accounts (Using the method we just added to AccountService)
        List<Account> myAccounts = accountService.getAccountsForUser(userEmail);

        // 2. Aggregate: Map Account Metadata + Real-Time Balance
        List<WalletSummary> summary = myAccounts.stream()
                .map(account -> new WalletSummary(
                        AccountResponse.fromEntity(account), // Reuse your existing DTO
                        ledgerService.calculateBalance(account.getId()) // The Aggregation Query
                ))
                .toList();

        return ResponseEntity.ok(summary);
    }

    /// Single Account Balance View.
    ///
    /// Allows the frontend to poll for the latest balance of a specific wallet.
    ///
    /// Security: Implements strict IDOR protection. The authenticated user MUST
    /// own the wallet ID requested in the path.
    ///
    /// @param id The Account UUID.
    /// @param authentication The security context from the JWT.
    /// @return The precise BigDecimal balance.
    /// @throws org.springframework.security.access.AccessDeniedException If the user does not own the wallet.
    @GetMapping("/{id}/balance")
    public ResponseEntity<BigDecimal> getBalance(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String requesterEmail = authentication.getName();

        // 1. Fetch Account Metadata
        Account targetAccount = accountService.getAccountById(id);

        // 2. IDOR Check: Ensure the requester owns this specific wallet
        if (!targetAccount.getUser().getEmail().equals(requesterEmail)) {
            log.warn("Security Alert: IDOR Attempt. User [{}] tried to access Wallet [{}] owned by [{}]",
                    requesterEmail, id, targetAccount.getUser().getEmail());
            throw new AccessDeniedException("Access Denied: You do not own this wallet.");
        }

        // 3. Safe to proceed
        return ResponseEntity.ok(ledgerService.calculateBalance(id));
    }

}