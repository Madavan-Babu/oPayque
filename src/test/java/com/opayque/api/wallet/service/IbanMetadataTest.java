package com.opayque.api.wallet.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Multi-Currency Account Management - IBAN Metadata Registry Audit.
///
/// This suite verifies the integrity and strictness of the [IbanMetadata] registry.
/// It ensures that the oPayque "Opaque" core correctly identifies supported IBAN
/// jurisdictions and explicitly rejects territories where IBAN routing is not
/// yet implemented or standardized.
class IbanMetadataTest {

    /// Registry Audit: Valid Territory Resolution.
    ///
    /// Confirms that primary European and Swiss territories (Tier 1 & 2) are correctly
    /// mapped to their ISO 13616 length and ISO 3166 country requirements.
    ///
    /// @param currencyCode The ISO 4217 code to be resolved (e.g., EUR, GBP).
    @ParameterizedTest
    @ValueSource(strings = {"EUR", "GBP", "CHF", "PLN"})
    @DisplayName("Registry: Should find metadata for supported Tier 1 & 2 currencies")
    void shouldFindSupportedCurrencies(String currencyCode) {
        IbanMetadata metadata = IbanMetadata.forCurrency(currencyCode);

        assertThat(metadata).isNotNull();
        assertThat(metadata.getCurrencyCode()).isEqualTo(currencyCode);

        // Basic Sanity: No valid IBAN length in the registry should be under 10 characters.
        assertThat(metadata.getTotalLength()).isGreaterThan(10);
    }

    /// Security Audit: Jurisdictional Gap Analysis.
    ///
    /// Validates the "Territory Gatekeeper" logic by ensuring that currencies from
    /// non-IBAN jurisdictions (e.g., INR) are strictly rejected.
    ///
    /// This prevents the system from accidentally generating malformed or
    /// non-standard financial identifiers for territories that utilize separate
    /// routing logic (e.g., IFSC/Account Number).
    @Test
    @DisplayName("Registry: Should strictly reject unsupported territories (Gap Analysis)")
    void shouldRejectUnsupportedTerritories() {
        // "INR" (India) represents a territory not supported for IBAN generation in Story 2.2.
        assertThatThrownBy(() -> IbanMetadata.forCurrency("INR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Territory not supported");
    }

    /// Boundary Check: Garbage Input Rejection.
    ///
    /// Confirms that arbitrary or malformed strings are rejected with an
    /// [IllegalArgumentException], preventing logic leakage into the generation engine.
    @Test
    @DisplayName("Registry: Should reject garbage input")
    void shouldRejectGarbage() {
        assertThatThrownBy(() -> IbanMetadata.forCurrency("LOL"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}