package com.opayque.api.wallet.controller;

import com.opayque.api.wallet.dto.AccountResponse;
import com.opayque.api.wallet.dto.CreateAccountRequest;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

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
}