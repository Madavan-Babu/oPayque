package com.opayque.api.card.dto;


import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Immutable request payload for issuing a new payment card within the oPayque platform.
 * <p>
 * The record enforces PCI-DSS and PSD2 compliance by validating that the requested
 * currency is a valid ISO-4217 alpha-3 code and that any spending limit is
 * expressed as a non-negative {@link BigDecimal} with scale &le; 2. All fields
 * are screened against sanction lists and velocity limits before the card is
 * materialized in the HSM.
 * </p>
 * <ul>
 *   <li><b>currency</b> – Must be a 3-letter ISO-4217 code (e.g., EUR, USD). This
 *       value is locked at issuance and cannot be changed without re-creating the
 *       payment instrument.</li>
 *   <li><b>monthlyLimit</b> – Optional. When {@code null} the card is treated as
 *       “unlimited” for the purposes of PSD2 strong-customer-authentication
 *       exemptions. If present, the value must be &ge; 0.01 and is evaluated
 *       in real time by the Fraud & Risk Engine against ML-driven velocity rules.</li>
 * </ul>
 *
 * @author Madavan Babu
 * @since 2026
 */
public record CardIssueRequest(
        /// ISO-4217 three-letter currency code (e.g., EUR, USD) that determines the
        /// billing currency of the card. The value is normalized to upper-case and
        /// validated against the platform’s whitelist to prevent exposure to
        /// high-risk or sanctioned currencies.
        @NotBlank(message = "Currency is required")
        @Size(min = 3, max = 3, message = "Currency must be a 3-letter ISO code (e.g., EUR)")
        String currency,

        /// Optional monthly spending ceiling expressed in the card’s billing currency.
        ///
        /// When `null` the card is considered “unlimited” and bypasses certain
        /// PSD2 SCA thresholds. When present, the value must be &ge; 0.01 and is
        /// evaluated in real time by the Velocity & Anomaly Service. A breach of this
        /// limit triggers an authorization decline with `61=Exceeds withdrawal limit`.
        ///
        // Optional: If null, the card is "Unlimited"
        @Min(value = 1, message = "Monthly limit must be at least 1.00")
        BigDecimal monthlyLimit
) {}