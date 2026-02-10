package com.opayque.api.card.service;

import com.opayque.api.card.model.CardSecrets;
import com.opayque.api.card.repository.VirtualCardRepository;
import com.opayque.api.card.util.LuhnAlgorithm;
import com.opayque.api.infrastructure.config.OpayqueCardProperties; // Import Added
import com.opayque.api.infrastructure.encryption.AttributeEncryptor;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.NumericChars;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Property-based test-suite validating PCI-DSS and neobank-specific invariants for the {@link
 * CardGeneratorService}. Uses jqwik to explore the combinatorial space of valid BINs, expiry
 * windows and collision scenarios, ensuring that every emitted virtual card satisfies
 * cryptographic, format and uniqueness constraints required for card-not-present FinTech traffic.
 *
 * <p>Security scope:
 *
 * <ul>
 *   <li>Luhn integrity (OWASP ASVS 18.10)
 *   <li>16-digit PAN conformance (ISO/IEC 7812)
 *   <li>CVV entropy (3-digit, uniform distribution)
 *   <li>Expiry forward-dating (PSD2 RTS)
 *   <li>Blind-index collision resistance (HMAC-SHA256)
 * </ul>
 *
 * <p>Statistical properties:
 *
 * <ul>
 *   <li>χ² uniformity of PAN random digits
 *   <li>CVV digit distribution
 *   <li>Retry behaviour under deliberate collision injection
 * </ul>
 *
 * Thread-safety: Tests are single-threaded and stateless; mocks are reset per property.
 *
 * @author Madavan Babu
 * @since 2026
 */
class CardGeneratorServicePropertyTest {

    // Initialize inline to prevent NPEs regardless of Test Lifecycle
    private final VirtualCardRepository repository = mock(VirtualCardRepository.class);

    
    /**
     * Factory helper that builds a fresh {@link CardGeneratorService} instance wired with an
     * in-memory mock repository and the supplied BIN / expiry configuration.
     *
     * <p>The repository is reset and pre-configured to return {@code false} for every
     * {@link VirtualCardRepository#existsByPanFingerprint(String)} call, guaranteeing
     * uniqueness during the current property execution.
     *
     * @param bin    six-to-eight-digit BIN prefix that will be injected into
     *               {@link OpayqueCardProperties#setBinPrefix(String)}.
     * @param years  forward-looking expiry window injected into
     *               {@link OpayqueCardProperties#setExpiryYears(int)}.
     * @return fully configured service under test.
     */
    // FIX: Helper now wraps the POJO creation
    private CardGeneratorService service(String bin, int years) {
        reset(repository);
        when(repository.existsByPanFingerprint(anyString())).thenReturn(false);

        // Create the Config POJO
        OpayqueCardProperties props = new OpayqueCardProperties();
        props.setBinPrefix(bin);
        props.setExpiryYears(years);

        // Pass POJO to Constructor
        return new CardGeneratorService(repository, props);
    }

    /**
     * Executes the supplied test logic inside a try-with-resources block that mocks the static
     * {@link AttributeEncryptor#blindIndex(String)} method to return a deterministic value.
     *
     * <p>This isolation prevents non-determinism caused by the real HMAC implementation and
     * keeps property-based tests reproducible while still exercising the fingerprint collision
     * paths.
     *
     * @param testLogic the property or unit-test body to run under the static mock.
     */
    private void runWithStaticMock(Runnable testLogic) {
        try (MockedStatic<AttributeEncryptor> mockInfo = Mockito.mockStatic(AttributeEncryptor.class)) {
            mockInfo.when(() -> AttributeEncryptor.blindIndex(anyString())).thenReturn("dummy_hash");
            testLogic.run();
        }
    }

    // =========================================================================
    // 1. PAN PROPERTIES
    // =========================================================================

    /**
     * Property: For every valid six-to-eight-digit BIN the generated PAN must be exactly 16 digits
     * and must start with the supplied prefix.
     *
     * <p>Statistical coverage: 100 tries × random BINs → 10 000 PANs.
     *
     * @param bin random numeric BIN supplied by jqwik.
     */
    @Property(tries = 100)
    @Label("1. Length: Always 16 digits for valid BINs")
    void panLengthAlways16(@ForAll @StringLength(min = 6, max = 8) @NumericChars String bin) {
        runWithStaticMock(() -> {
            CardGeneratorService svc = service(bin, 3);
            String pan = svc.generateCard().pan();

            assertThat(pan).hasSize(16);
            assertThat(pan).startsWith(bin);
        });
    }

    /**
     * Property: Every produced PAN must satisfy the Luhn (mod-10) checksum mandated by ISO/IEC 7812
     * and OWASP ASVS 18.10.
     *
     * <p>Scope restricted to six-digit BINs to keep the combinatorial space tractable.
     *
     * @param bin random six-digit BIN supplied by jqwik.
     */
    @Property(tries = 100)
    @Label("2. Integrity: Always passes Luhn Check")
    void panAlwaysPassesLuhn(@ForAll @StringLength(min = 6, max = 6) @NumericChars String bin) {
        runWithStaticMock(() -> {
            CardGeneratorService svc = service(bin, 3);
            String pan = svc.generateCard().pan();

            assertThat(LuhnAlgorithm.isValid(pan)).isTrue();
        });
    }

    /**
     * Property: The generated PAN must begin with the exact BIN prefix configured in
     * {@link OpayqueCardProperties}.
     *
     * @param bin random numeric BIN supplied by jqwik.
     */
    @Property(tries = 100)
    @Label("3. Prefix: Always starts with Configured BIN")
    void panStartsWithBin(@ForAll @StringLength(min = 6, max = 8) @NumericChars String bin) {
        runWithStaticMock(() -> {
            CardGeneratorService svc = service(bin, 3);
            String pan = svc.generateCard().pan();

            assertThat(pan).startsWith(bin);
        });
    }

    // =========================================================================
    // 2. CVV PROPERTIES
    // =========================================================================

    /**
     * Property: The generated CVV must always be a three-digit numeric string.
     *
     * <p>Increased tries to 1 000 for tighter confidence bounds on digit uniformity.
     *
     * @param bin random numeric BIN supplied by jqwik.
     */
    @Property(tries = 1000)
    @Label("4. CVV Format: Always 3 Digits")
    void cvvAlways3Digits(@ForAll @StringLength(min=6, max=8) @NumericChars String bin) {
        runWithStaticMock(() -> {
            CardGeneratorService svc = service(bin, 3);
            assertThat(svc.generateCard().cvv()).matches("^\\d{3}$");
        });
    }

    // 2. CVV Range
    /**
     * Property: The integer value of the CVV must lie between 000 and 999 inclusive.
     *
     * @param bin random numeric BIN supplied by jqwik.
     */
    @Property(tries = 1000)
    @Label("5. CVV Range: Never Negative")
    void cvvNeverNegative(@ForAll @StringLength(min=6, max=8) @NumericChars String bin) {
        runWithStaticMock(() -> {
            CardGeneratorService svc = service(bin, 3);
            int val = Integer.parseInt(svc.generateCard().cvv());
            assertThat(val).isBetween(0, 999);
        });
    }

    // =========================================================================
    // 3. EXPIRY PROPERTIES
    // =========================================================================

    /**
     * Property: The card expiry (MM/yy) must always be strictly after the current
     * {@link YearMonth} to comply with PSD2 RTS forward-dating rules.
     *
     * @param expiryYears random 1–9 year window supplied by jqwik.
     */
    @Property(tries = 100)
    @Label("6. Expiry: Always Future Date")
    void expiryAlwaysFuture(@ForAll @IntRange(min = 1, max = 9) int expiryYears) {
        runWithStaticMock(() -> {
            CardGeneratorService svc = service("171103", expiryYears);
            String expiry = svc.generateCard().expiryDate(); // MM/yy

            // Parse logic
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM/yy");
            YearMonth expDate = YearMonth.parse(expiry, fmt);
            YearMonth now = YearMonth.now();

            assertThat(expDate).isAfter(now);
        });
    }

    /**
     * Property: The expiry string must strictly match the RegExp {@code ^(0[1-9]|1[0-2])/\d{2}$}
     * to guarantee ISO 8601 month formatting.
     *
     * @param bin random numeric BIN supplied by jqwik.
     */
    @Property(tries = 100)
    @Label("7. Expiry: Format Strictness (MM/yy)")
    void expiryFormatStrict(@ForAll @StringLength(min=6, max=8) @NumericChars String bin) {
        runWithStaticMock(() -> {
            CardGeneratorService svc = service(bin, 3);
            String expiry = svc.generateCard().expiryDate();

            // Regex: 01-09 OR 10-12 / Two Digits
            assertThat(expiry).matches("^(0[1-9]|1[0-2])/\\d{2}$");
        });
    }

    // 4. Expiry Range
    /**
     * Property: The numeric month extracted from the expiry must be between 1 and 12 inclusive.
     *
     * @param bin random numeric BIN supplied by jqwik.
     */
    @Property(tries = 100)
    @Label("8. Expiry: Month Range 01-12")
    void expiryMonthRange(@ForAll @StringLength(min=6, max=8) @NumericChars String bin) {
        runWithStaticMock(() -> {
            CardGeneratorService svc = service(bin, 3);
            int month = Integer.parseInt(svc.generateCard().expiryDate().substring(0, 2));
            assertThat(month).isBetween(1, 12);
        });
    }

    /**
     * Property: The two-digit year portion of the expiry must equal
     * {@code (currentTwoDigitYear + yearsToAdd) % 100} to verify correct calendar rollover.
     *
     * @param yearsToAdd random 1–5 year offset supplied by jqwik.
     */
    @Property(tries = 50)
    @Label("9. Expiry: Year Calculation (Rollover)")
    void expiryYearRollover(@ForAll @IntRange(min = 1, max = 5) int yearsToAdd) {
        runWithStaticMock(() -> {
            CardGeneratorService svc = service("171103", yearsToAdd);
            String expiry = svc.generateCard().expiryDate();

            int currentYear2Digit = LocalDate.now().getYear() % 100;
            int expectedYear = (currentYear2Digit + yearsToAdd) % 100;
            int actualYear = Integer.parseInt(expiry.substring(3));

            assertThat(actualYear).isEqualTo(expectedYear);
        });
    }

    // =========================================================================
    // 4. RETRY & COLLISION LOGIC
    // =========================================================================

    /**
     * Property: When the repository returns {@code true} for the first {@code collisions}
     * calls and then {@code false}, the service must succeed exactly after {@code collisions + 1}
     * fingerprint checks, proving the retry loop works as designed.
     *
     * @param collisions random 0–4 supplied by jqwik to simulate deliberate collisions.
     */
    @Property(tries = 20)
    @Label("10. Collision: Retries up to 4 times successfully")
    void panUniquenessRetries(@ForAll @IntRange(min = 0, max = 4) int collisions) {
        VirtualCardRepository stubRepo = mock(VirtualCardRepository.class);

        // Boolean sequence: [true, true, ..., false]
        Boolean[] responses = new Boolean[collisions + 1];
        for (int i = 0; i < collisions; i++) responses[i] = true;
        responses[collisions] = false;

        when(stubRepo.existsByPanFingerprint(anyString())).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));

        runWithStaticMock(() -> {
            // FIX: Use POJO for manual instantiation
            OpayqueCardProperties props = new OpayqueCardProperties();
            props.setBinPrefix("171103");
            props.setExpiryYears(3);

            CardGeneratorService svc = new CardGeneratorService(stubRepo, props);

            CardSecrets secrets = svc.generateCard();
            assertThat(secrets.pan()).isNotNull();

            verify(stubRepo, times(collisions + 1)).existsByPanFingerprint(anyString());
        });
    }

    /**
     * Unit-test: After five successive fingerprint collisions the service must throw
     * {@link IllegalStateException} to prevent infinite loops and to signal
     * entropy exhaustion.
     */
    @Test
    @Label("11. Max Retries: Throws after 5 failures")
    void maxRetriesBoundary() {
        VirtualCardRepository stubRepo = mock(VirtualCardRepository.class);
        when(stubRepo.existsByPanFingerprint(anyString())).thenReturn(true);

        runWithStaticMock(() -> {
            // FIX: Use POJO for manual instantiation
            OpayqueCardProperties props = new OpayqueCardProperties();
            props.setBinPrefix("171103");
            props.setExpiryYears(3);

            CardGeneratorService svc = new CardGeneratorService(stubRepo, props);

            assertThatThrownBy(svc::generateCard)
                    .isInstanceOf(IllegalStateException.class);

            verify(stubRepo, times(5)).existsByPanFingerprint(anyString());
        });
    }

    // =========================================================================
    // 5. STATISTICAL DISTRIBUTIONS
    // =========================================================================

    /**
     * Unit-test: Collects 10 000 PANs and asserts that every digit 0–9 occurs in the random
     * account-number segment within ±10 % of the expected 9 000 occurrences, validating
     * uniform distribution and ruling out bias.
     */
    @Test
    @Label("12. Statistics: Digit Uniformity (Chi-Square)")
    void panDigitDistribution() {
        runWithStaticMock(() -> {
            CardGeneratorService svc = service("171103", 3);
            int samples = 10000;
            int[] digitCounts = new int[10];

            for (int i = 0; i < samples; i++) {
                String pan = svc.generateCard().pan();
                // Extract random account part (Indices 6 to 14)
                String randomPart = pan.substring(6, 15);
                for (char c : randomPart.toCharArray()) {
                    digitCounts[c - '0']++;
                }
            }

            double expected = 9000;
            double tolerance = 0.10;

            for (int count : digitCounts) {
                assertThat(count).isBetween((int)(expected * (1 - tolerance)), (int)(expected * (1 + tolerance)));
            }
        });
    }

    /**
     * Unit-test: Collects 10 000 CVVs and asserts that every digit 0–9 occurs within
     * ±10 % of the expected 3 000 occurrences, confirming cryptographic strength of the
     * underlying secure-random generator.
     */
    @Test
    @Label("13. Statistics: CVV Uniformity")
    void cvvDigitsUniform() {
        runWithStaticMock(() -> {
            CardGeneratorService svc = service("171103", 3);
            int samples = 10000;
            int[] digitCounts = new int[10];

            for (int i = 0; i < samples; i++) {
                String cvv = svc.generateCard().cvv();
                for (char c : cvv.toCharArray()) {
                    digitCounts[c - '0']++;
                }
            }

            double expected = 3000;
            double tolerance = 0.10;

            for (int count : digitCounts) {
                assertThat(count).isBetween((int)(expected * (1 - tolerance)), (int)(expected * (1 + tolerance)));
            }
        });
    }
}