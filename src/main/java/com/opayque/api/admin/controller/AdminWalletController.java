package com.opayque.api.admin.controller;

import com.opayque.api.admin.dto.AccountStatusUpdateRequest;
import com.opayque.api.admin.dto.AdminAccountResponse;
import com.opayque.api.admin.dto.AdminDepositResponse;
import com.opayque.api.admin.dto.MoneyDepositRequest;
import com.opayque.api.admin.service.AdminWalletService;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.infrastructure.ratelimit.RateLimiterService;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.AccountStatus;
import com.opayque.api.wallet.entity.LedgerEntry;
import com.opayque.api.wallet.service.AccountService;
import com.opayque.api.wallet.service.LedgerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Administrative REST controller that enables privileged users to manage
 * end‑user wallet accounts.
 * <p>
 * The controller operates under the {@code ADMIN} role and delegates all
 * business rules to {@link AdminWalletService}. It provides two distinct
 * operations:
 * <ul>
 *   <li><strong>Account status updates</strong> – Allows an administrator to
 *   transition an {@link Account} between {@link AccountStatus} values (e.g.
 *   {@code ACTIVE} → {@code CLOSED}). The endpoint extracts the admin's
 *   email from the {@link Authentication} object, ensuring the action is
 *   auditable.</li>
 *   <li><strong>Fund injection (deposit)</strong> – Enables a privileged
 *   administrator to credit any wallet (the so‑called “Fiat God” mode). The
 *   request payload is represented by {@link MoneyDepositRequest} and is
 *   processed through the core {@link LedgerService} to guarantee atomic
 *   locking, rate‑limiting, and proper ledger entry creation.</li>
 * </ul>
 * <p>
 * Each operation returns a safe DTO that masks sensitive fields (IBAN,
 * owner email) to avoid serialization pitfalls and to protect PII.
 *
 * @author Madavan Babu
 * @since 2026
 *
 * @see AdminWalletService
 * @see AccountService
 * @see RateLimiterService
 * @see LedgerService
 * @see AdminAccountResponse
 * @see AccountStatusUpdateRequest
 * @see MoneyDepositRequest
 */
@RestController
@RequestMapping("/api/v1/admin/accounts")
@RequiredArgsConstructor
public class AdminWalletController {

    private final AdminWalletService adminWalletService;

    /**
     * <p>Updates the status of a specific account as performed by an administrator.</p>
     * <p>The method extracts the administrator's email from the {@link Authentication}
     * object, delegates the status change to {@link AdminWalletService}, and returns a
     * safe DTO that masks sensitive fields to prevent lazy‑loading recursion errors.</p>
     *
     * @param id {@code UUID} identifier of the target account.
     * @param request {@code AccountStatusUpdateRequest} containing the new {@link AccountStatus}.
     * @param authentication {@code Authentication} representing the current security context; the admin's email is obtained via {@code authentication.getName()}.
     *
     * @return {@code ResponseEntity<AdminAccountResponse>} wrapping the updated account
     *         data suitable for API consumers.
     *
     * @see AdminWalletService
     * @see AccountService
     * @see UserRepository
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminAccountResponse> updateAccountStatus(
            @PathVariable UUID id,
            @Valid @RequestBody AccountStatusUpdateRequest request,
            Authentication authentication
    ) {
        // 1. Extract Admin Context
        String adminEmail = authentication.getName();

        // 2. Delegate to Service
        Account updatedAccount = adminWalletService.updateAccountStatus(adminEmail, id, request.status());

        // 3. Map to Safe DTO (Prevents 500 Recursion/Lazy Errors)
        return ResponseEntity.ok(AdminAccountResponse.fromEntity(updatedAccount));
    }

    /**
     * Handles an administrator‑initiated fund injection ("minting") into a specific wallet.
     * <p>
     * The method extracts the admin’s email from the {@link Authentication} object, then
     * delegates the core operation to {@link AdminWalletService#depositFunds(String, UUID, MoneyDepositRequest)}.
     * The service enforces rate‑limiting, audit logging, and atomic ledger entry creation,
     * guaranteeing that the deposit is recorded consistently and securely.
     * </p>
     *
     * @param id               {@code UUID} identifier of the target wallet that will receive the funds.
     * @param request          {@link MoneyDepositRequest} containing the amount, currency, and optional description.
     * @param authentication   {@link Authentication} representing the current security context; the admin’s email
     *                         is obtained via {@code authentication.getName()}.
     *
     * @return {@code ResponseEntity<AdminDepositResponse>} wrapping a safe DTO derived from the created
     *         {@link LedgerEntry}, suitable for API consumers without exposing internal entity relations.
     *
     * @see AdminWalletService
     * @see LedgerService
     * @see UserRepository
     */
    @PostMapping("/{id}/deposit")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminDepositResponse> depositFunds(
            @PathVariable UUID id,
            @Valid @RequestBody MoneyDepositRequest request,
            Authentication authentication
    ) {
        // 1. Audit: Who is printing money?
        String adminEmail = authentication.getName();

        // 2. Execute: Service handles Locking, Rate Limiting, and Ledger Entry
        var ledgerEntry = adminWalletService.depositFunds(adminEmail, id, request);

        // 3. Transform: Map Entity -> Safe DTO (Prevents 500 Recursion Error)
        return ResponseEntity.ok(AdminDepositResponse.fromEntity(ledgerEntry));
    }
}