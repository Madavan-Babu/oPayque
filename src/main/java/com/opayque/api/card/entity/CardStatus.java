package com.opayque.api.card.entity;

import java.util.Set;

/**
 * Defines the operational state of a payment instrument within the oPayque ecosystem.
 * <p>
 * The lifecycle of a card is governed by PCI-DSS, PSD2, and internal AML/KYC policies.
 * Each status transition is immutably logged in the secure audit trail
 * </p>
 * <ul>
 *   <li><b>ACTIVE</b> – Card is fully operational; all authorization workflows (3-DS, PIN, CDCVM) are enabled.</li>
 *   <li><b>FROZEN</b> – Temporary suspension, typically triggered by fraud-detection ML models or customer self-service.
 *       Authorizations are declined with <code>59=Suspected fraud</code> unless the transaction is whitelisted.</li>
 *   <li><b>TERMINATED</b> – Permanent revocation; card is added to the EMV-Certificate Revocation List (CRL)
 *       and hot-listed in the HSM. Re-issuance requires a new PAN and fresh key material.</li>
 * </ul>
 *
 * @author Madavan Babu
 * @since 2026
 */

public enum CardStatus {
    /**
     * Card is operational and authorized for all financial transactions.
     * <p>
     * Real-time risk scoring is still applied; a transaction may still be declined
     * by the Velocity & Anomaly Service without changing this status.
     * </p>
     */
    ACTIVE,
    /**
     * Temporary protective suspension.
     * <p>
     * While frozen, the card cannot participate in new authorizations. Pre-approved
     * recurring payments (MIT) continue until explicitly revoked. Status can be
     * reversed to {@link #ACTIVE} after strong-customer-authentication (SCA) and
     * fraud-case disposition.
     * </p>
     */
    FROZEN,
    /**
     * Permanent termination of the card relationship.
     * <p>
     * All tokens (Google Pay, Apple Pay, Samsung Pay) are remotely deleted, the PAN
     * is added to the internal blacklist, and a zeroization request is sent to
     * the secure-element provider. This action is irrevocable within the platform.
     * </p>
     */
    TERMINATED;

    /**
     * State Machine Logic: Validation of lifecycle transitions.
     *
     * @param nextState The proposed new state.
     * @return true if the transition is architecturally valid; false otherwise.
     */
    public boolean canTransitionTo(CardStatus nextState) {
        // 1. Identity Transition (A -> A) is always harmless/valid.
        if (this == nextState) return true;

        // 2. Define Valid Transitions based on CURRENT state.
        return switch (this) {
            case ACTIVE -> Set.of(FROZEN, TERMINATED).contains(nextState);
            case FROZEN -> Set.of(ACTIVE, TERMINATED).contains(nextState);
            case TERMINATED -> false; // The Iron Rule: Dead cards stay dead.
        };
    }
}
