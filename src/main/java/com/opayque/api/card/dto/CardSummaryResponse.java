package com.opayque.api.card.dto;

import com.opayque.api.card.entity.CardStatus;
import java.util.UUID;

/**
 * Lightweight, read-only projection of a payment card optimized for high-frequency UI rendering and
 * secure audit logging within the oPayque issuing platform.
 *
 * <p>This DTO intentionally omits sensitive data elements (full PAN, CVV, cryptographic keys) to
 * satisfy PCI-DSS Req-3.4 and PSD2 strong-customer-authentication (SCA) data-minimization mandates.
 * All string fields are sanitized through the {@code SanitizingJsonSerializer} to prevent XSS
 * injection when exposed via REST or GraphQL gateways.
 *
 * <p>The {@link #maskedPan} format adheres to ISO/IEC 7813-3 and local regulator masking rules:
 * first-six/last-four with middle digits replaced by asterisks. The {@link #expiryDate} is returned
 * as {@code MM/yy} to facilitate client-side validation without exposing the full 4-digit year.
 *
 * <p>Cardinality guarantees:
 *
 * <ul>
 *   <li>{@link #cardId} – globally unique, immutable, and traceable across ledger entries.
 *   <li>{@link #currency} – ISO-4217 alpha-3 code; enforced by {@code CurrencyValidator}.
 *   <li>{@link #status} – governed by the state machine defined in {@link CardStatus}.
 * </ul>
 *
 * <p>Thread-safety: record is immutable; safe for concurrent access without synchronization.
 *
 * @author Madavan Babu
 * @since 2026
 */
public record CardSummaryResponse(
    UUID cardId,
    String maskedPan, // **** **** **** 4444
    String expiryDate,
    String cardholderName,
    String currency,
    CardStatus status
) {}