package com.opayque.api.admin.service;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminWalletService {

    private final AccountService accountService;
    private final RateLimiterService rateLimiterService;
    private final UserRepository userRepository;
    private final LedgerService ledgerService;

    /**
     * Orchestrates the account status update with strict governance checks.
     */
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