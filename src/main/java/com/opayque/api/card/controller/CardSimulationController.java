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
 * Public-facing endpoint for simulating external card network events.
 * <p>
 * acts as a "Mock Merchant" or "Payment Gateway" callback.
 * * <b>Security Note:</b> This controller accepts raw card data (PAN/CVV).
 * Ensure strict TLS encryption is active in production.
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