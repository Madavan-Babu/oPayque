package com.opayque.api.transactions.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * Unit-test harness for {@link TransferRequest} DTO validation rules.
 * <p>
 * Validates the strict input contracts required by the oPayque Atomic Transfer Engine.
 * Ensures that only well-formed, sanction-screened, and precision-safe requests are
 * accepted into the downstream transactional boundary, thereby reducing attack surface
 * for injection-style fraud and preserving ACID integrity across ledger movements.
 * </p>
 *
 * @author Madavan Babu
 * @version 2.0.0
 * @since 2026
 */
class TransferRequestTest {

    private static Validator validator;

    /**
     * Bootstrap JSR-380 validator once per JVM to avoid repeated factory creation.
     * <p>
     * A single shared {@link Validator} instance guarantees consistent validation
     * semantics across all test cases and mirrors the runtime security posture enforced
     * by the transaction service layer.
     * </p>
     */
    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    /**
     * Asserts that a syntactically correct, positive-sum, ISO-currency request passes
     * validation—mirroring the golden path used by production orchestration flows.
     * <p>
     * Success here implies that the DTO can safely cross the controller boundary and
     * enter the transactional service layer where {@code PESSIMISTIC_WRITE} locks ensure
     * serializable ledger updates and ACID compliance.
     * </p>
     */
    @Test
    @DisplayName("Should pass validation for a perfectly valid request")
    void shouldAcceptValidRequest() {
        TransferRequest request = new TransferRequest(
                "bob@opayque.com",
                "100.50",
                "USD"
        );

        Set<ConstraintViolation<TransferRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    /**
     * Validates that negative or otherwise malformed monetary strings are rejected
     * at the edge—before any floating-point interpretation can occur.
     * <p>
     * This acts as an anti-fraud guardrail, preventing credit-style injection attacks
     * that attempt to exploit rounding behaviours inside the BigDecimal engine.
     * </p>
     */
    @Test
    @DisplayName("Should reject negative amounts (Regex Check)")
    void shouldRejectInvalidAmounts() {
        // Case 1: Negative (Fails because '-' is not allowed in the regex)
        TransferRequest negativeReq = new TransferRequest("bob@opayque.com", "-10.00", "USD");

        assertThat(validator.validate(negativeReq))
                .extracting(ConstraintViolation::getMessage)
                // FIX: Match the exact message defined in the DTO @Pattern
                .anyMatch(msg -> msg.contains("Amount must be a positive number with up to 2 decimal places"));
    }

    /**
     * Ensures that only RFC-5322 compliant addresses are accepted, aligning with
     * KYC/AML identity verification requirements and preventing impersonation attempts
     * through malformed identifiers.
     */
    @Test
    @DisplayName("Should reject invalid email formats")
    void shouldRejectInvalidEmail() {
        TransferRequest request = new TransferRequest(
                "bob-at-gmail.com", // Missing @
                "100.00",
                "USD"
        );

        assertThat(validator.validate(request))
                .extracting(ConstraintViolation::getMessage)
                // FIX: Match the actual "Receiver..." prefix
                .anyMatch(msg -> msg.contains("Receiver must be a well-formed email address"));
    }

    /**
     * Confirms strict ISO-4217 3-letter currency enforcement, eliminating typo-squatting
     * or test tokens (e.g., "USDT") from entering the FX normalization pipeline.
     */
    @Test
    @DisplayName("Should reject invalid currency codes (Must be ISO 3-Letter)")
    void shouldRejectInvalidCurrency() {
        // Too short
        TransferRequest shortCurr = new TransferRequest("bob@opayque.com", "100.00", "US");
        assertThat(validator.validate(shortCurr)).hasSize(1);

        // Too long
        TransferRequest longCurr = new TransferRequest("bob@opayque.com", "100.00", "USDT");
        assertThat(validator.validate(longCurr)).hasSize(1);
    }

    /**
     * Verifies that non-numeric amount literals (e.g., "one-hundred") are blocked,
     * closing the door on injection-style payloads that could later trigger parsing
     * exceptions or bypass business-rule checks inside the service layer.
     */
    @Test
    @DisplayName("Should reject malformed number strings")
    void shouldRejectNonNumericAmount() {
        TransferRequest badNum = new TransferRequest("bob@opayque.com", "one-hundred", "USD");

        assertThat(validator.validate(badNum))
                .extracting(ConstraintViolation::getMessage)
                // FIX: Match the exact message defined in the DTO @Pattern
                .anyMatch(msg -> msg.contains("Amount must be a positive number with up to 2 decimal places"));
    }
}