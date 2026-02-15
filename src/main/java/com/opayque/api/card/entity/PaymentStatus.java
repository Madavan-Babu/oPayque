package com.opayque.api.card.entity;

/**
 * Strict enumeration of possible outcomes for an external card transaction simulation.
 *
 * <p>This enum is used in API responses to represent transaction statuses in a client-facing,
 * machine-readable format, eliminating reliance on "magic strings". Each status code is directly
 * referenced in the idempotency validation workflow (e.g., ensuring that approved transactions do
 * not trigger duplicate debits across clustered payment services).
 *
 * <p><b>Mapping to External Systems:</b>
 *
 * <ul>
 *   <li><b>APPROVED</b> - Transaction successfully authorized
 *   <li><b>DECLINED</b> - General authorization failure
 *   <li><b>INSUFFICIENT_FUNDS</b> - Funding balance too low
 *   <li><b>CARD_EXPIRED</b> - Card not within valid date range
 *   <li><b>CARD_FROZEN</b> - Account-level restriction
 *   <li><b>LIMIT_EXCEEDED</b> - Spending limit threshold breached
 * </ul>
 *
 * <p><b>Architectural Context:</b> Values are synchronized with the error classification framework
 * used by the <code>IdempotencyService</code> to enforce settlement finality per SWIFT CBPR+ §3.4
 * duplicate detection requirements.
 *
 * @author Madavan Babu
 * @since 2026
 */
public enum PaymentStatus {
    APPROVED,
    DECLINED,
    INSUFFICIENT_FUNDS,
    CARD_EXPIRED,
    CARD_FROZEN,
    LIMIT_EXCEEDED
}