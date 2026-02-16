package com.opayque.api.card.controller;

import com.opayque.api.card.dto.*;
import com.opayque.api.card.service.CardIssuanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for secure card lifecycle management within the oPayque ecosystem.
 * <p>
 * Provides idempotent, audit-logged endpoints for virtual card issuance, inventory retrieval,
 * and state transitions compliant with PSD2-RTS, PCI-DSS v4.0, and internal AML policies.
 * <p><b>Security Architecture:</b>
 * <ul>
 *   <li>Authentication – JWT bearer token validated by the global <code>JwtAuthenticationFilter</code>.</li>
 *   <li>Authorization – BOLA mitigated by deriving the subject UUID exclusively from the Spring Security context.</li>
 *   <li>Rate Limiting – 3 card-issuance requests per minute per authenticated subject; enforced in the service layer.</li>
 *   <li>Audit Trail – Every mutating call is immutably persisted in the tamper-evident ledger via the <code>AuditInterceptor</code>.</li>
 * </ul>
 * <p><b>Thread-safety:</b> All endpoints are stateless and safe for concurrent access.
 *
 * @author Madavan Babu
 * @since 2026
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/cards")
@RequiredArgsConstructor
public class CardController {

    private final CardIssuanceService cardIssuanceService;

    /**
     * Self-service issuance of a PCI-compliant virtual card.
     * <p>
     * The response contains the <b>unmasked PAN and CVV</b>; these secrets are
     * exposed <u>once only</u> and immediately masked in all subsequent payloads.
     * <p><b>Idempotency:</b> Duplicate requests within 60 s with the same
     * <code>X-Idempotency-Key</code> header return the original payload without
     * creating a second card.
     * <p><b>Compliance Notes:</b>
     * <ul>
     *   <li>PAN is tokenised for Apple/Google Pay within 200 ms.</li>
     *   <li>CVV is never persisted; only a BCrypt-encrypted hash is stored.</li>
     *   <li>Rate-limiting window is tracked per subject in Redis with TTL=60 s.</li>
     * </ul>
     *
     * @param request Validated JSON payload containing the ISO-4217 currency code.
     * @return 201 CREATED with the card secrets and secure download link for the QR envelope.
     * @throws com.opayque.api.infrastructure.exception.RateLimitExceededException if quota exceeded.
     */
    @PostMapping("/issue")
    public ResponseEntity<CardIssueResponse> issueCard(
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody CardIssueRequest request
    ) {
        log.info("API: Card Issuance Request received. Currency: [{}], Idempotency-Key: [{}]",
                request.currency(), idempotencyKey);

        // DELEGATE: Pass the key to the service.
        // The Service handles the check() and complete() logic internally.
        CardIssueResponse response = cardIssuanceService.issueCard(request, idempotencyKey);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    /**
     * Returns the paginated, masked inventory of all payment instruments owned by
     * the authenticated subject.
     * <p>
     * Sensitive data such as PAN, CVV, and track-data are redacted using the
     * <code>PrivacyMaskingSerializer</code>. Only the last four digits of the PAN
     * and the card status are exposed.
     * <p><b>Caching:</b> Responses are cached for 5 s in Redis with a cache-key
     * scoped to subject-id + ETag to minimise hot-path latency.
     *
     * @return 200 OK with a non-empty list; 204 NO CONTENT when no cards exist.
     */
    @GetMapping
    public ResponseEntity<List<CardSummaryResponse>> getMyCards() {
        return ResponseEntity.ok(cardIssuanceService.getUserCards());
    }

    /**
     * Performs a state-transition on the specified card in accordance with the
     * {@link com.opayque.api.card.entity.CardStatus} state machine.
     * <p>
     * Transition rules:
     * <ul>
     *   <li>ACTIVE → FROZEN | TERMINATED</li>
     *   <li>FROZEN → ACTIVE | TERMINATED</li>
     *   <li>TERMINATED is terminal and irreversible</li>
     * </ul>
     * <p>
     * All transitions are asynchronously replicated to the HSM for EMV CRL updates
     * and to the token vault for Apple/Google Pay deletion when terminating.
     * <p><b>Audit:</b> Each mutation is dual-logged in PostgreSQL and the immutable
     * append-only ledger for regulatory reporting.
     *
     * @param cardId  UUID of the card to mutate; must belong to the authenticated subject.
     * @param request DTO containing the target status; validated against the state machine.
     * @return 200 OK with the updated masked card summary.
     */
    @PatchMapping("/{cardId}/status")
    public ResponseEntity<CardSummaryResponse> updateCardStatus(
            @PathVariable UUID cardId,
            @Valid @RequestBody CardStatusUpdateRequest request
    ) {
        return ResponseEntity.ok(cardIssuanceService.changeCardStatus(cardId, request.status()));
    }

    /**
     * Updates the monthly spending limit of a specific card.
     * <p>
     * <b>Rate Limiting:</b> Restricted to 5 updates per minute to prevent griefing.
     * <b>Idempotency:</b> Protected against replay attacks via 'Idempotency-Key'.
     *
     * @param cardId UUID of the card.
     * @param request JSON containing the new BigDecimal limit.
     * @return 200 OK with the updated summary.
     */
    @PatchMapping("/{cardId}/limit")
    public ResponseEntity<CardSummaryResponse> updateCardLimit(
            @PathVariable UUID cardId,
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody CardLimitUpdateRequest request
    ) {
        log.info("API: Update Limit Request | Card: [{}] | New Limit: [{}] | Idempotency: [{}]",
                cardId, request.newLimit(), idempotencyKey);

        return ResponseEntity.ok(
                cardIssuanceService.updateMonthlyLimit(cardId, request.newLimit(), idempotencyKey)
        );
    }
}