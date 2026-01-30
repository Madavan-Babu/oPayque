package com.opayque.api.wallet.service;

import com.opayque.api.wallet.repository.AccountRepository;
import net.jqwik.api.*;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// Multi-Currency Account Management - IBAN Generation Property Audit.
///
/// This suite utilizes the JQwik engine to verify jurisdictional IBAN
/// generation properties. Unlike standard unit tests, this executes hundreds of
/// random iterations to ensure the generator satisfies ISO 13616 and ISO 7064
/// mathematical constraints across all supported territories.
class IbanGeneratorPropertiesTest {

    /// Mock of the atomic sequence provider to simulate database-backed
    /// identifier generation.
    private final AccountRepository accountRepository = mock(AccountRepository.class);

    /// The concrete implementation under audit, injected with the mocked repository.
    private final IbanGenerator generator = new IbanGeneratorImpl(accountRepository);

    /// Verifies that all generated IBANs adhere to the ISO 13616 structural format.
    ///
    /// This property test validates:
    /// 1. Structural Match: The output follows the pattern (Country 2)(Check 2)(BBAN X).
    /// 2. Checksum Integrity: The resulting string must satisfy the ISO 7064 Mod 97 algorithm.
    ///
    /// @param currency A randomly generated currency code from the supported registry.
    @Property
    @Label("Structure: Generated IBANs must satisfy ISO 13616 format")
    void ibansShouldFollowStructure(@ForAll("supportedCurrencies") String currency) {
        // Arrange: Simulate the PostgreSQL sequence returning a fixed high-entropy identifier.
        when(accountRepository.getNextAccountNumber()).thenReturn(123456789L);

        // Act: Generate the territorial IBAN.
        String iban = generator.generate(currency);

        // Assert: Validates the alphanumeric structure and jurisdictional format.
        assertThat(iban).matches("^[A-Z]{2}[0-9]{2}[A-Z0-9]+$");

        // Assert: Validates mathematical integrity using the Mod 97-10 check.
        assertThat(isValidIso7064(iban)).isTrue();
    }

    /// Defines the domain of supported ISO 4217 currencies for property generation.
    /// This ensures testing is confined to territories currently provisioned in metadata.
    @Provide
    Arbitrary<String> supportedCurrencies() {
        return Arbitraries.of("EUR", "GBP", "CHF", "NOK");
    }

    /// Internal Helper: ISO 7064 Mod 97-10 Checksum Verification.
    ///
    /// Implements the manual verification algorithm:
    /// 1. Rearrange components (BBAN + Country + Check).
    /// 2. Numeric conversion (Alpha -> Integer).
    /// 3. Large integer modulo 97 validation (Must equal 1).
    private boolean isValidIso7064(String iban) {
        String reformatted = iban.substring(4) + iban.substring(0, 4);
        StringBuilder numeric = new StringBuilder();
        for (char ch : reformatted.toCharArray()) {
            numeric.append(Character.getNumericValue(ch));
        }
        return new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }
}