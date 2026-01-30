package com.opayque.api.wallet.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/// Multi-Currency Account Management - IBAN Jurisdiction Registry.
///
/// Serves as the authoritative registry for supported IBAN territories.
/// Mappings align with ISO 13616 (IBAN Registry), ISO 3166 (Country Codes),
/// and ISO 4217 (Currency Codes).
///
/// Constraint: Only territories within the IBAN jurisdiction are supported.
/// Non-IBAN routing (e.g., USD via Fedwire) requires a separate implementation strategy.
@Getter
@RequiredArgsConstructor
public enum IbanMetadata {

    /// Tier 1: Major Jurisdictions
    /// Eurozone - Defaults to Germany (DE).
    EUR("EUR", "DE", 22),

    /// United Kingdom (GB).
    GBP("GBP", "GB", 22),

    /// Switzerland (CH).
    CHF("CHF", "CH", 21),

    /// Tier 2: Nordic & Central Europe
    /// Poland (PL).
    PLN("PLN", "PL", 28),

    /// Norway (NO).
    NOK("NOK", "NO", 15),

    /// Denmark (DK).
    DKK("DKK", "DK", 18),

    /// Sweden (SE).
    SEK("SEK", "SE", 24),

    /// Czechia (CZ).
    CZK("CZK", "CZ", 24),

    /// Tier 3: Emerging Europe
    /// Hungary (HU).
    HUF("HUF", "HU", 28),

    /// Romania (RO).
    RON("RON", "RO", 24);

    /// ISO 4217 Currency Code.
    private final String currencyCode;

    /// ISO 3166 Alpha-2 Country Code.
    private final String countryCode;

    /// Total character length defined by ISO 13616 for the jurisdiction.
    private final int totalLength;

    /// Immutable cache for O(1) jurisdiction lookup.
    private static final Map<String, IbanMetadata> LOOKUP_MAP = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(IbanMetadata::getCurrencyCode, Function.identity()));

    /// Performs a strict jurisdictional lookup.
    ///
    /// Acts as a security gate to reject unsupported territories (e.g., JPY, CAD, USD)
    /// before they reach the generation engine.
    ///
    /// @param currencyCode The ISO 4217 code to validate.
    /// @return The associated metadata for the territory.
    /// @throws IllegalArgumentException If the territory is outside supported IBAN jurisdiction.
    public static IbanMetadata forCurrency(String currencyCode) {
        IbanMetadata metadata = LOOKUP_MAP.get(currencyCode);
        if (metadata == null) {
            throw new IllegalArgumentException("Territory not supported for IBAN generation: " + currencyCode);
        }
        return metadata;
    }
}