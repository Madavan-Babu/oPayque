package com.opayque.api.wallet.service;

/// Multi-Currency Account Management - Identifier Generation Contract.
///
/// Defines the strategy for creating International Bank Account Numbers (IBAN).
/// Implementations must adhere to ISO 13616 standards and ensure
/// collision-free generation within the oPayque ecosystem.
public interface IbanGenerator {

    /// Generates a valid IBAN for the specified currency territory.
    ///
    /// @param currency The ISO 4217 currency code determining the jurisdiction.
    /// @return A validated, standardized IBAN string.
    String generate(String currency);
}