package com.opayque.api.architecture;

import com.opayque.api.identity.security.JwtAuthenticationFilter;
import com.opayque.api.infrastructure.util.SecurityUtil;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

/// **oPayque Structural Guardrails - Comprehensive Security Policy**
///
/// This suite enforces the application's security posture at the compiler level.
/// It covers four main pillars:
/// 1. **Identity Isolation (Runtime):** Prevents BOLA by restricting access to the Security Context.
/// 2. **Data Protection (Persistence):** Prevents leaks by enforcing Encryption on sensitive fields.
/// 3. **Cryptographic Strength (Logic):** Prevents weak RNG usage in financial calculations.
/// 4. **Data Leakage Prevention (API):** Prevents accidental exposure of raw secrets.
@AnalyzeClasses(packages = "com.opayque.api", importOptions = ImportOption.DoNotIncludeTests.class)
class SecurityArchitectureTest {

    // =================================================================================
    // SECTION 1: IDENTITY ISOLATION (BOLA PREVENTION)
    // =================================================================================

    /// Ensures that no class outside of the designated security infrastructure
    /// depends on {@link SecurityContextHolder}.
    @ArchTest
    static final ArchRule security_context_should_be_isolated = noClasses()
            .that().doNotHaveFullyQualifiedName(JwtAuthenticationFilter.class.getName())
            .and().doNotHaveFullyQualifiedName(SecurityUtil.class.getName())
            .should().dependOnClassesThat().belongToAnyOf(SecurityContextHolder.class)
            .because("Direct access to SecurityContextHolder is forbidden; use SecurityUtil for identity lookups to prevent BOLA vulnerabilities.");

    // =================================================================================
    // SECTION 2: DATA ENCRYPTION (PCI-DSS LITE)
    // =================================================================================

    /// **Guardrail: Mandatory Encryption**
    ///
    /// Rule: Any field in a Database Entity named "pan", "cvv", or "secret"
    /// MUST have the JPA @Convert annotation (triggering the Encryption Engine).
    @ArchTest
    static final ArchRule sensitive_fields_must_be_encrypted =
            fields()
                    .that().areDeclaredInClassesThat().areAnnotatedWith(Entity.class)
                    .and().haveNameMatching("(?i).*(pan|cvv|secret|ssn|expiry).*")
                    .should().beAnnotatedWith(Convert.class)
                    .orShould().haveName("password") // Exception 1: BCrypt Hashing
                    .orShould().haveNameMatching("(?i).*fingerprint") // Exception 2: HMAC Blind Indexing
                    .orShould().beDeclaredInClassesThat().haveSimpleName("RefreshToken") // Exception 3: Token Expiry is structural, not financial
                    .because("Sensitive financial data (PAN, CVV, Expiry) must be encrypted at rest.");

    /// **Guardrail: Non-Nullable Sensitive Data**
    ///
    /// Rule: Sensitive fields (PAN/CVV) must be annotated with @Column(nullable = false).
    /// We don't want to encrypt "null" values, and banking data shouldn't be optional.
    @ArchTest
    static final ArchRule sensitive_fields_must_not_be_nullable =
            fields()
                    .that().areDeclaredInClassesThat().areAnnotatedWith(Entity.class)
                    .and().haveNameMatching("(?i).*(pan|cvv).*")
                    .should(beMarkedNonNullable())
                    .because("Sensitive financial fields cannot be null in the database schema.");


    // =================================================================================
    // SECTION 3: CRYPTOGRAPHIC RANDOMNESS (STORY 4.2)
    // =================================================================================

    /// **Guardrail: Ban Weak Randomness**
    ///
    /// Rule: Classes in the Card domain MUST NOT use java.util.Random.
    /// Weak PRNGs allow attackers to predict future card numbers if they discover the seed.
    @ArchTest
    static final ArchRule no_weak_randomness_in_card_domain =
            noClasses()
                    .that().resideInAPackage("..card..")
                    .should().dependOnClassesThat().haveFullyQualifiedName("java.util.Random")
                    .because("java.util.Random is cryptographically weak. Use java.security.SecureRandom for financial data.");

    /// **Guardrail: Ban Math.random()**
    ///
    /// Rule: Classes in the Card domain MUST NOT call Math.random().
    /// This is just a wrapper around java.util.Random and is equally insecure.
    @ArchTest
    static final ArchRule no_math_random_in_card_domain =
            noClasses()
                    .that().resideInAPackage("..card..")
                    .should().callMethod(Math.class, "random")
                    .because("Math.random() is cryptographically weak. Use java.security.SecureRandom for financial data.");

    // =================================================================================
    // SECTION 4: DATA LEAKAGE PREVENTION
    // =================================================================================

    /// **Guardrail: Prevent Raw Secrets Exposure**
    ///
    /// Rule: No Public Method in a RestController can return the `CardSecrets` record directly.
    /// `CardSecrets` contains raw PAN and CVV. Returning it directly risks exposing it via default JSON serialization
    /// in endpoints where it wasn't intended. It must be mapped to a specific DTO.
    @ArchTest
    static final ArchRule card_secrets_should_never_be_returned_by_controllers =
            methods()
                    .that().arePublic()
                    .and().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                    .should().notHaveRawReturnType("com.opayque.api.card.model.CardSecrets")
                    .because("CardSecrets contains raw PAN/CVV data. Do not return it directly from Controllers; map it to a safe DTO.");

    // =================================================================================
    // SECTION 5: CRYPTOGRAPHIC INTEGRITY (CONFIGURATION LOCK)
    // =================================================================================

    /// **Guardrail: Salt Prefix Lock**
    ///
    /// Rule: The `SALT_PREFIX` in AttributeEncryptor MUST match the hardcoded value.
    /// Why: If this string changes by even one character, the derived keys will change,
    /// and ALL existing encrypted data in the database will become unreadable garbage.
    @ArchTest
    static final ArchRule salt_prefix_must_be_locked =
            classes()
                    .that().haveFullyQualifiedName("com.opayque.api.infrastructure.encryption.AttributeEncryptor")
                    .should(haveConstantValue("SALT_PREFIX", "OPAYQUE_MIASMA_SALT"))
                    .because("The Encryption Salt Prefix is cryptographically bound to existing data. Changing it will render all cards unreadable.");

    // =================================================================================
    // HELPER CONDITIONS
    // =================================================================================

    private static ArchCondition<JavaField> beMarkedNonNullable() {
        return new ArchCondition<>("be marked as @Column(nullable = false)") {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                // 1. Check if @Column exists
                if (!field.isAnnotatedWith(Column.class)) {
                    // Technically a failure, but we rely on JPA defaults (which are nullable=true)
                    events.add(SimpleConditionEvent.violated(field,
                            field.getFullName() + " is sensitive but missing @Column annotation"));
                    return;
                }

                // 2. Check the "nullable" property of the annotation
                boolean isNullable = field.getAnnotationOfType(Column.class).nullable();

                if (isNullable) {
                    events.add(SimpleConditionEvent.violated(field,
                            field.getFullName() + " is SENSITIVE but marked nullable=true. Security Risk."));
                }
            }
        };
    }

    /**
     * Reflectively checks that a static field has a specific value.
     */
    private static ArchCondition<com.tngtech.archunit.core.domain.JavaClass> haveConstantValue(String fieldName, String expectedValue) {
        return new ArchCondition<>("have a constant field '" + fieldName + "' with value '" + expectedValue + "'") {
            @Override
            public void check(com.tngtech.archunit.core.domain.JavaClass item, ConditionEvents events) {
                try {
                    // 1. Load the actual runtime class
                    Class<?> clazz = item.reflect();

                    // 2. Access the field
                    java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);

                    // 3. Get the value (static context -> null instance)
                    Object actualValue = field.get(null);

                    // 4. Assert Equality
                    if (!expectedValue.equals(actualValue)) {
                        String message = String.format("CRITICAL SECURITY FAIL: Field '%s' in %s was changed to '%s'. It MUST remain '%s' to prevent data loss.",
                                fieldName, item.getSimpleName(), actualValue, expectedValue);
                        events.add(SimpleConditionEvent.violated(item, message));
                    }
                } catch (NoSuchFieldException e) {
                    events.add(SimpleConditionEvent.violated(item, "Field '" + fieldName + "' is missing! The encryption engine might be broken."));
                } catch (Exception e) {
                    events.add(SimpleConditionEvent.violated(item, "Could not verify salt lock: " + e.getMessage()));
                }
            }
        };
    }
}