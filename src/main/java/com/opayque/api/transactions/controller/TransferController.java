package com.opayque.api.transactions.controller;

import com.opayque.api.infrastructure.ratelimit.RateLimiterService;
import com.opayque.api.transactions.dto.TransferRequest;
import com.opayque.api.transactions.dto.TransferResponse;
import com.opayque.api.transactions.service.TransferService;
import com.opayque.api.wallet.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/// **Story 3.3: The Secure Transfer Gateway**.
///
/// Exposes the internal Transfer Engine to the outside world via a secured REST Endpoint.
///
/// **Security Features:**
/// - **Rate Limiting:** Enforces traffic quotas via Redis before processing.
/// - **Idempotency:** Extracts strict `Idempotency-Key` headers to prevent double-charging.
/// - **Context Awareness:** Resolves the `Sender` strictly from the JWT (Principal), never the body.
/// - **Input Validation:** Strict JSR-303 validation on the DTO.
@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
@Slf4j
public class TransferController {

    private final TransferService transferService;
    private final RateLimiterService rateLimiterService;
    private final AccountService accountService;

    /// Initiates a P2P fund transfer.
    ///
    /// @param idempotencyKey Unique client-generated key (UUID format recommended) to ensure atomicity.
    /// @param request The strict DTO containing receiver, amount, and currency.
    /// @param authentication The Spring Security context (injected automatically from JWT).
    /// @return A 200 OK receipt with the transaction ID.
    @PostMapping
    public ResponseEntity<TransferResponse> initiateTransfer(
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request,
            Authentication authentication
    ) {
        // 1. Context Resolution (Who is calling?)
        String senderEmail = authentication.getName();

        // 2. The Traffic Cop (Rate Limiting)
        // Fails Fast: If this throws, we save DB connections and processing power.
        rateLimiterService.checkLimit(senderEmail);

        log.info("Transfer initiated by [{}] -> Target: [{}]", senderEmail, request.receiverEmail());

        // 3. The BOLA Check (Broken Object Level Authorization Prevention)
        // We resolve the Account ID strictly from the authenticated email.
        // The client cannot forge a "senderId" in the request body.
        // Note: accountService.getAccountIdByEmail() must return UUID to satisfy ArchUnit (No Entity Leak).
        // Pass the currency from the request to ensure we grab the right wallet!
        UUID senderId = accountService.getAccountIdByEmail(senderEmail, request.currency());

        // 4. Execution (The Atomic Engine)
        UUID transferId = transferService.transferFunds(
                senderId,
                request.receiverEmail(),
                request.amount(),
                request.currency(),
                idempotencyKey
        );

        // 5. The Receipt (DTO Mapping)
        TransferResponse response = new TransferResponse(
                transferId,
                "COMPLETED",
                Instant.now().toString()
        );

        return ResponseEntity.ok(response);
    }
}