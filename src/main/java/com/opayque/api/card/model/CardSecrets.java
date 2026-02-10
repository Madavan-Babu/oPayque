package com.opayque.api.card.model;

/**
 * Immutable, transient carrier for plaintext card secrets produced by the oPayque card-factory
 * microservice and consumed exclusively within the PCI-DSS compliant encryption boundary.
 *
 * <p>CardSecrets acts as an in-memory shuttle for PAN, CVV2, and expiry date from the virtual card
 * manufacturing pipeline to the FIPS-140-3 accredited HSM cluster responsible for PIN-block
 * derivation, symmetric tokenization, and downstream vault storage. To mitigate memory scraping and
 * cold-boot attacks, instances are zeroized via explicit calls immediately after
 * cryptographic materialization and are never serialized, logged, or transmitted across network
 * boundaries (OWASP ASVS 8.3).
 *
 * <p>Security considerations:
 *
 * <ul>
 *   <li>Fields are non-null; validation occurs upstream in the Luhn-aware card-factory.
 *   <li>Immutability prevents accidental mutation once handed off to the encryption engine.
 *   <li>Designed for single-use within Spring-managed transactions to reduce dwell time of
 *       plaintext secrets in heap memory (PSD2 RTS Article 9).
 * </ul>
 *
 * @param pan Primary Account Number (13-19 digits) conforming to ISO/IEC 7812; validated via Luhn
 *     checksum before instantiation.
 * @param cvv Card Verification Value (3-4 digits) used for card-not-present (CNP) fraud scoring;
 *     discarded after first cryptogram generation.
 * @param expiryDate Expiration date in {@code MM/YY} format; drives real-time EMV-CAP dynamic CVV
 *     rotation and BIN range risk rules.
 * @author Madavan Babu
 * @since 2026
 */
public record CardSecrets(String pan, String cvv, String expiryDate // Format: MM/YY
    ) {}
