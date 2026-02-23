package com.opayque.api.admin.service;

import com.opayque.api.admin.controller.AdminWalletController;
import com.opayque.api.admin.dto.MoneyDepositRequest;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.infrastructure.ratelimit.RateLimiterService;
import com.opayque.api.wallet.dto.CreateLedgerEntryRequest;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.AccountStatus;
import com.opayque.api.wallet.entity.LedgerEntry;
import com.opayque.api.wallet.entity.TransactionType;
import com.opayque.api.wallet.service.AccountService;
import com.opayque.api.wallet.service.LedgerService;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Service layer that provides privileged administrative operations on user wallets.
 * <p>
 * The {@code AdminWalletService} aggregates core domain services
 * {@link AccountService}, {@link LedgerService} and auxiliary infrastructure
 * {@link RateLimiterService} to enforce strict governance when an administrator
 * modifies account status or injects funds.  It resolves the administrator’s
 * identity via {@link UserRepository} and records all actions with audit‑level
 * logging, thereby supporting regulatory compliance and operational traceability.
 * <p>
 * Typical use‑cases include:
 * <ul>
 *   <li>Changing the {@link AccountStatus} of any account under a
 *       supervision context.</li>
 *   <li>Performing a “top‑up” (deposit) into a wallet while applying rate limiting,
 *       currency conversion, and immutable ledger entry creation.</li>
 * </ul>
 *
 * @author Madavan Babu
 * @since 2026
 *
 * @see AdminWalletController
 * @see AccountService
 * @see LedgerService
 * @see RateLimiterService
 * @see UserRepository
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminWalletService {

    private final AccountService accountService;
    private final RateLimiterService rateLimiterService;
    private final UserRepository userRepository;
    private final LedgerService ledgerService;

    /**
     * Updates the {@link AccountStatus} of a target {@link Account} under administrative authority.
     * <p>
     * The method performs three core steps:
     * <ul>
     *   <li>Resolves the admin identity from {@code adminEmail} using {@link UserRepository};
     *       an {@link AccessDeniedException} is thrown if the admin cannot be located.</li>
     *   <li>Applies a rate‑limit check via {@link RateLimiterService} to protect against
     *       excessive admin actions (max 10 calls per defined window).</li>
     *   <li>Delegates the actual status transition to {@link AccountService#updateAccountStatus(UUID, AccountStatus, boolean)},
     *       ensuring domain rules (e.g., {@link AccountStatus#canTransitionTo(AccountStatus)}) are enforced.</li>
     * </ul>
     * <p>
     * This orchestrated approach centralises audit logging, governance, and domain validation,
     * thereby preserving integrity of account state changes across the system.
     *
     * @param adminEmail       the email address of the administrator initiating the request;
     *                         used to locate the {@link User} performing the operation.
     * @param targetAccountId  the unique identifier of the {@link Account} whose status is to be changed.
     * @param newStatus        the desired {@link AccountStatus} to apply to the target account.
     *
     * @return the updated {@link Account} instance reflecting the new status.
     *
     * @see AdminWalletController
     * @see AccountService
     * @see RateLimiterService
     * @see UserRepository
     */
    @Timed(value = "opayque.admin.status.update", description = "Throughput and latency of administrative account state changes")
    @Transactional
    public Account updateAccountStatus(String adminEmail, UUID targetAccountId, AccountStatus newStatus) {
        // 1. Identity Resolution (Fixes the UUID vs Email conflict cleanly)
        User admin = userRepository.findByEmail(adminEmail)
                .orElseThrow(() -> new AccessDeniedException("Admin context invalid: User not found"));

        UUID adminId = admin.getId();

        log.warn("ADMIN ORCHESTRATION: Status Change. Admin=[{}] Target=[{}] NewStatus=[{}]",
                adminId, targetAccountId, newStatus);

        // 2. Governance: Internal Rate Limiting
        rateLimiterService.checkLimit(adminId.toString(), "admin_action", 10);

        // 3. Execution: Delegate to Core Domain
        return accountService.updateAccountStatus(targetAccountId, newStatus, true);
    }

    /**
     * Injects funds into a target account ("Top-Up").
     * <p>
     * This operation is strictly governed:
     * 1. Rate-Limited to prevent accidental double-execution.
     * 2. Audit-logged with a distinct "ADMIN_DEPOSIT" prefix.
     * 3. Executed via the core LedgerService to ensure atomic locking and currency conversion.
     *
     * @param adminEmail The email of the admin performing the action (from SecurityContext).
     * @param targetAccountId The wallet receiving the funds.
     * @param request The deposit details (Amount, Currency, Description).
     * @return The resulting immutable LedgerEntry.
     */
    @Timed(value = "opayque.admin.money.deposit", description = "Throughput and latency of administrative manual fund injections")
    @Transactional
    public LedgerEntry depositFunds(String adminEmail, UUID targetAccountId, MoneyDepositRequest request) {
        // 1. Identity Resolution & Governance
        User admin = userRepository.findByEmail(adminEmail)
                .orElseThrow(() -> new AccessDeniedException("Admin context invalid: User not found"));

        UUID adminId = admin.getId();

        log.warn("ADMIN MONEY INJECTION: Initiated. Admin=[{}] Target=[{}] Amount=[{} {}]",
                adminId, targetAccountId, request.amount(), request.currency());

        // 2. Rate Limiting (Critical for Money Operations)
        // Limit: 5 deposits per minute per admin to prevent script loops/accidents
        rateLimiterService.checkLimit(adminId.toString(), "admin_deposit", 5);

        // 3. Construct Robust Ledger Request
        // We sanitize the description to ensure the audit trail is clear
        String auditDescription = "ADMIN_DEPOSIT: " +
                (request.description() != null ? request.description() : "Manual Top-Up");

        CreateLedgerEntryRequest ledgerRequest = new CreateLedgerEntryRequest(
                targetAccountId,
                request.amount(),
                request.currency(),
                TransactionType.CREDIT, // <--- The "Minting" Action
                auditDescription,
                LocalDateTime.now(),
                UUID.randomUUID() // Unique Ref ID for this deposit
        );

        // 4. Execute via Core Ledger Engine
        // This handles DB Locking, Currency Conversion, and Persistence automatically.
        return ledgerService.recordEntry(ledgerRequest);
    }
}