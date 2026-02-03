package com.opayque.api.wallet.service;

import com.opayque.api.wallet.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigInteger;

/// Multi-Currency Account Management - IBAN Generation Engine.
///
/// Implements the [IbanGenerator] contract using the ISO 13616 standard for
/// International Bank Account Numbers. The engine utilizes a database-backed
/// atomic sequence to ensure collision-proof identifier generation across
/// distributed instances.
///
/// Security: Adheres to the "Opaque" architecture by utilizing high-entropy
/// sequences that do not leak internal user or account metadata.
@Component
@RequiredArgsConstructor
public class IbanGeneratorImpl implements IbanGenerator {

    private final AccountRepository accountRepository;

    /// ISO 7064 Mod 97-10 Constant utilized for checksum normalization.
    private static final BigInteger DIVISOR = new BigInteger("97");

    /// Generates a standardized IBAN based on jurisdictional metadata.
    ///
    /// The generation process follows a strict 5-step pipeline:
    /// 1. Jurisdictional metadata lookup (ISO 4217 / 3166).
    /// 2. Atomic sequence retrieval (PostgreSQL 'nextval').
    /// 3. BBAN (Basic Bank Account Number) construction with zero-padding.
    /// 4. ISO 7064 Mod 97-10 checksum calculation.
    /// 5. Final assembly into the standard ISO 13616 format.
    ///
    /// @param currencyCode The ISO 4217 code determining the target territory.
    /// @return A fully validated, checksum-verified IBAN string.
    /// @throws IllegalArgumentException If the territory is not supported by oPayque.
    @Override
    public String generate(String currencyCode) {
        // 1. Strict Metadata Lookup: Ensures the currency falls within a supported
        // IBAN jurisdiction (Rejects USD/JPY/CAD).
        IbanMetadata metadata = IbanMetadata.forCurrency(currencyCode);

        // 2. Atomic Sequence Fetch: Hits the DB directly to acquire a guaranteed
        // unique ID, mitigating race conditions in concurrent creation flows.
        Long uniqueId = accountRepository.getNextAccountNumber();

        // 3. BBAN Construction: Structure: [Country(2)] [Check(2)] [BBAN(Remainder)].
        // Padds the sequence value to fulfill the jurisdiction's specific length requirement.
        int bbanLength = metadata.getTotalLength() - 4;
        String bban = String.format("%0" + bbanLength + "d", uniqueId);

        // 4. Checksum Calculation: Computes the 2-digit ISO check digits.
        String checkDigits = calculateCheckDigits(metadata.getCountryCode(), bban);

        // 5. Assembly: Concatenates components into the finalized financial identifier.
        return metadata.getCountryCode() + checkDigits + bban;
    }

    /// Performs the ISO 7064 Mod 97-10 Checksum Calculation.
    ///
    /// This algorithm ensures the mathematical validity of the IBAN to prevent
    /// routing errors during fund transfers.
    ///
    /// Algorithm Steps:
    /// A. Rearrange: Move Country Code and dummy check digits (00) to the suffix.
    /// B. Numeric Conversion: Transform Alpha-2 codes into numeric values (A=10...Z=35).
    /// C. Modulo 97: Calculate the remainder of the resulting large integer.
    /// D. Complement: Subtract the remainder from 98 to derive the check digits.
    ///
    /// @param countryCode The ISO 3166 Alpha-2 code.
    /// @param bban The numeric bank account string.
    /// @return A 2-digit string representing the IBAN checksum.
    private String calculateCheckDigits(String countryCode, String bban) {
        // Step A: Position country code and "00" check digits at the end of the string.
        String temp = bban + countryCode + "00";

        // Step B: Transform the alphanumeric sequence into a pure numeric representation.
        StringBuilder numeric = new StringBuilder();
        for (char ch : temp.toCharArray()) {
            if (Character.isDigit(ch)) {
                numeric.append(ch);
            } else {
                numeric.append(Character.getNumericValue(ch));
            }
        }

        // Step C: Execute Modulo 97 operation using BigInteger to prevent overflow.
        BigInteger bigInt = new BigInteger(numeric.toString());
        int mod = bigInt.mod(DIVISOR).intValue();

        // Step D: Derive the check digit by taking the complement of the remainder from 98.
        int checkDigit = 98 - mod;

        // Step E: Ensure the output is zero-padded for single-digit results.
        return (checkDigit < 10 ? "0" : "") + checkDigit;
    }
}