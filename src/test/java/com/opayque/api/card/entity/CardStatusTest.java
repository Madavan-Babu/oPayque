package com.opayque.api.card.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-level security & compliance test-suite for [CardStatus] state-machine.
 *
 * Guarantees that every card-lifecycle transition adheres to PCI-DSS, PSD2-SCA
 * and internal risk-management policies. Any illegal transition (e.g.
 * TERMINATED → ACTIVE) is rejected to prevent fraud-reactivation attacks.
 *
 * @author Madavan Babu
 * @since 2026
 */

class CardStatusTest {

    // =========================================================================
    // THE MATRIX: 100% State Transition Coverage
    // =========================================================================
    /**
     * Tests every possible permutation of state transitions.
     * <p>
     * <b>Coverage Justification:</b>
     * <ul>
     * <li><b>Identity (A->A):</b> Covered by ACTIVE->ACTIVE, etc. (Hits the 'if (this == nextState)' guard)</li>
     * <li><b>Active Logic:</b> Covered by ACTIVE->FROZEN (True) and ACTIVE->TERMINATED (True).</li>
     * <li><b>Frozen Logic:</b> Covered by FROZEN->ACTIVE (True) and FROZEN->TERMINATED (True).</li>
     * <li><b>Iron Rule:</b> Covered by TERMINATED->ACTIVE (False) and TERMINATED->FROZEN (False).</li>
     * </ul>
     * Validates PCI-compliant card-state transitions via parameterized matrix.
     *
     * Covers 100 % of the transition surface: identity, reversible, and terminal.
     * Any deviation will break PSD2 “strong customer authentication” continuity
     * and is therefore flagged as a critical regression.
     *
     * @param current  source state under test
     * @param next     target state requested
     * @param expected transition verdict (true = allowed)
     */
    @ParameterizedTest(name = "{index} :: {0} -> {1} should be {2}")
    @CsvSource({
            // SOURCE,      TARGET,      EXPECTED_RESULT
            // -----------------------------------------
            // 1. Identity Transitions (Always True)
            "ACTIVE,      ACTIVE,      true",
            "FROZEN,      FROZEN,      true",
            "TERMINATED,  TERMINATED,  true",

            // 2. Active Transitions
            "ACTIVE,      FROZEN,      true",
            "ACTIVE,      TERMINATED,  true",

            // 3. Frozen Transitions
            "FROZEN,      ACTIVE,      true",
            "FROZEN,      TERMINATED,  true",

            // 4. Terminated Transitions (The Iron Rule: No Escape)
            "TERMINATED,  ACTIVE,      false",
            "TERMINATED,  FROZEN,      false"
    })
    void shouldValidateStateTransitionsCorrectly(CardStatus current, CardStatus next, boolean expected) {
        // Act
        boolean result = current.canTransitionTo(next);

        // Assert
        assertThat(result)
                .as("Transition from %s to %s", current, next)
                .isEqualTo(expected);
    }

    // =========================================================================
    // EDGE CASES: Null Safety & Robustness
    // =========================================================================

    /**
     * Ensures null-proof robustness for downstream AML & fraud-detection modules.
     *
     * A null pointer here could leak into ledger reconciliation, violating
     * the “fail-secure” principle. The test enforces fast-fail via NPE,
     * aligning with ISO 27001 secure-coding guidelines.
     */
    @Test
    @DisplayName("Should handle NULL transition gracefully (or fail fast)")
    void shouldHandleNullNextState() {
        // Let's assert the behavior consistent with Java's Set.of() contract.
        assertThatThrownBy(() -> CardStatus.ACTIVE.canTransitionTo(null))
                .isInstanceOf(NullPointerException.class);
    }
}