package com.opayque.api.card.dto;

import com.opayque.api.card.entity.CardStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Immutable request DTO for altering the operational state of a payment card within the oPayque
 * platform.
 *
 * <p>This record is consumed by the secured {@code /cards/{id}/status} endpoint and drives the
 * PCI-DSS compliant state machine. Every transition is validated against the card's current status
 * and the caller's granted authorities (ROLE_CUSTOMER, ROLE_FRAUD_OPS, ROLE_ADMIN). All mutations
 * are idempotent, ACID-protected, and immutably logged to the tamper-evident audit trail for
 * AML/KYC supervision.
 *
 * <p>Supported transitions:
 *
 * <ul>
 *   <li>{@link CardStatus#ACTIVE} → {@link CardStatus#FROZEN} or {@link CardStatus#TERMINATED}
 *   <li>{@link CardStatus#FROZEN} → {@link CardStatus#ACTIVE} or {@link CardStatus#TERMINATED}
 *   <li>{@link CardStatus#TERMINATED} – terminal state; no further transitions allowed
 * </ul>
 *
 * <p>Security considerations:
 *
 * <ul>
 *   <li>Requires strong customer authentication (SCA) via MFA or FIDO2 for non-admin callers.
 *   <li>Status change events are published to the fraud-detection Kafka topic for ML model
 *       retraining.
 *   <li>Concurrent updates are serialized at the database level using optimistic locking (version
 *       column).
 * </ul>
 *
 * @param status the target operational state; must be non-null and a valid enum constant.
 * @author Madavan Babu
 * @since 2026
 */
public record CardStatusUpdateRequest(
        @NotNull(message = "Status is required")
        CardStatus status
) {}