package com.opayque.api.card.controller;

import com.opayque.api.card.dto.CardTransactionRequest;
import com.opayque.api.card.dto.CardTransactionResponse;
import com.opayque.api.card.service.CardTransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;



/**
 * REST façade that exposes a deterministic, idempotent endpoint for simulating card-based Point-of-Sale transactions.
 * <p>
 * This controller acts as the north-bound gateway for ISO-8583 style payloads and is intentionally kept thin; all
 * business rules (risk scoring, velocity checks, merchant limits, etc.) are delegated to the {@link CardTransactionService}
 * to preserve a clean separation of concerns and to simplify unit testing of the web layer.
 * <p>
 * The resource path {@code /api/v1/simulation/card-transaction} is versioned and is designed to remain stable across
 * minor releases.  Every request is fully logged (PII masked) and is traceable via the {@code externalTransactionId}
 * supplied by the caller.
 * <p>
 * <b>Thread-safety:</b> The class is stateless and relies on injected dependencies that are themselves thread-safe.
 *
 * @author Madavan Babu
 * @since 2026
 * @see CardTransactionService
 * @see CardTransactionRequest
 * @see CardTransactionResponse
 */
@RestController
@RequestMapping("/api/v1/simulation")
@RequiredArgsConstructor
@Slf4j
public class CardSimulationController {

    private final CardTransactionService cardTransactionService;

    /**
     * Simulates a Point-of-Sale (POS) card swipe.
     *
     * @param request The ISO-8583 style payload containing card details and amount.
     * @return The authorization decision (Approved/Declined) and transaction metadata.
     */
    @PostMapping("/card-transaction")
    public ResponseEntity<CardTransactionResponse> processTransaction(
            @Valid @RequestBody CardTransactionRequest request
    ) {
        log.info("Simulation Request Received | Merchant: {} | ExtID: {}",
                request.getMerchantName(), request.getExternalTransactionId());

        // Delegate strictly to the Service Layer
        CardTransactionResponse response = cardTransactionService.processTransaction(request);

        return ResponseEntity.ok(response);
    }
}