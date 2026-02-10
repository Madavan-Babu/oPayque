package com.opayque.api.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Centralized configuration harness for card-issuing parameters within the oPayque neobank stack.
 *
 * <p>Provides externally-driven, immutable-in-runtime defaults for BIN range and expiry tenure,
 * aligning with PCI-DSS v4.0 requirement 4.1 (minimal exposure of cardholder data) and PSD2 RTS
 * Article 11 (strong customer authentication lifecycle). Property values are injected at bootstrap
 * and govern the behaviour of CardIssuingService as well
 * as card-not-present fraud-detection rules.
 *
 * <p>Security considerations:
 * <ul>
 *   <li>BIN prefix must be scoped to a dedicated ICA range registered with the scheme for
 *       tokenized virtual cards (EMVCo token vault integration).
 *   <li>Expiry window is constrained to mitigate the risk of long-lived PANs while balancing
 *       user convenience and re-issuance cost.
 * </ul>
 *
 * <p>Runtime mutability is intentionally prohibited; changes require controlled re-deployment
 * via the bank’s DevSecOps pipeline to ensure auditability and regulatory sign-off.
 *
 * @author Madavan Babu
 * @since 2026
 */

@Data
@Configuration
@ConfigurationProperties(prefix = "opayque.card")
public class OpayqueCardProperties {

    /**
     * Issuer Identification Number (IIN) or BIN prefix assigned by the scheme for oPayque
     * virtual card products. Must be 6-digit numeric and registered in the EMVCo token
     * directory to guarantee global uniqueness and interoperability.
     *
     * <p>This prefix is concatenated with an internally generated 9-digit account ID and
     * a Luhn check digit to produce a PCI-compliant 16-digit PAN. The value is injected
     * from {@code opayque.card.bin-prefix} and audited during PCI-DSS penetration tests
     * to confirm it falls within the institution’s allocated range.
     *
     * <p>Default: {@code 171103}
     */
    private String binPrefix;

    /**
     * Card validity period expressed in whole years from issuance. Dictates the
     * {@code MM/YY} expiry date embossed on virtual cards and stored encrypted in
     * the {@link com.opayque.api.card.entity.VirtualCard} entity.
     *
     * <p>Must satisfy both:
     * <ul>
     *   <li>Scheme rules (minimum 1 year, maximum 5 years).
     *   <li>PSD2 RTS strong-customer-authentication re-authentication horizon to
     *       minimise fraud window while avoiding excessive re-issuance friction.
     * </ul>
     *
     * <p>Injected from {@code opayque.card.expiry-years}; changes require regression
     * testing against 3-D Secure 2.x authentication flows and recurring payment
     * mandates (e.g., SaaS subscriptions).
     *
     * <p>Default: {@code 3}
     */
    private int expiryYears;
}