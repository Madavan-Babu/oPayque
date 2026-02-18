package com.opayque.api.admin.controller;

import com.opayque.api.admin.dto.AccountStatusUpdateRequest;
import com.opayque.api.admin.dto.AdminAccountResponse; // <--- Import DTO
import com.opayque.api.admin.dto.AdminDepositResponse;
import com.opayque.api.admin.dto.MoneyDepositRequest;
import com.opayque.api.admin.service.AdminWalletService;
import com.opayque.api.wallet.entity.Account;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/accounts")
@RequiredArgsConstructor
public class AdminWalletController {

    private final AdminWalletService adminWalletService;

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
     * "Fiat God" Mode: Allows Admins to inject funds (Credit) into any account.
     * Used for demos, refunds, or manual adjustments.
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