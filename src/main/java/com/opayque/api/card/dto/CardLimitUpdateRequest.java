package com.opayque.api.card.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Immutable request payload for updating the monthly spending limit of a payment card within the oPayque platform.
 * <p>
 * This record enforces PCI-DSS and PSD2 compliance by validating that the requested limit is a non-negative
 * {@link BigDecimal} with scale ≤ 2. The new limit is evaluated in real-time by the Velocity & Anomaly
 * Service against ML-driven dynamic thresholds. A zero value is treated as “unlimited” for PSD2 strong-customer-
 * authentication exemptions.
 * <p>
 * The underlying card entity is updated via an idempotent PUT to {@code /cards/{cardId}/limits} and the change
 * is audited in a tamper-evident card-event log with automatic re-synchronization to the HSM. The limit is
 * reset to the new value at 00:00 UTC on the 1st of each month unless a different cycle is configured.
 * <p>
 * Limits are expressed in the card's billing currency (ISO-4217) and cannot exceed the global platform ceiling
 * defined by the {@link com.opayque.api.card.entity.VirtualCard}. Exceeding this ceiling results in a 400-BadRequest
 * without side effects.
 *
 * @param newLimit  The new monthly spending ceiling expressed in the card's billing currency.
 *                  Value must be ≥ 0.00 and is validated by {@link DecimalMin}.
 *
 * @author  Madavan Babu
 * @since 2026
 * @see      com.opayque.api.card.controller.CardController
 * @see      com.opayque.api.card.service.CardLimitService
 * @see      com.opayque.api.card.repository.VirtualCardRepository
 */
public record CardLimitUpdateRequest(
        @NotNull(message = "New limit is required")
        @DecimalMin(value = "0.00", message = "Limit cannot be negative")
        BigDecimal newLimit
) {}