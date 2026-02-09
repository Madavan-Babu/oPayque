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

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/// **oPayque Structural Guardrails - Comprehensive Security Policy**
///
/// This suite enforces the application's security posture at the compiler level.
/// It covers two main pillars:
/// 1. **Identity Isolation (Runtime):** Prevents BOLA by restricting access to the Security Context.
/// 2. **Data Protection (Persistence):** Prevents leaks by enforcing Encryption on sensitive fields.
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
                    .and().haveNameMatching("(?i).*(pan|cvv|secret|ssn).*") // Case-insensitive Regex
                    .should().beAnnotatedWith(Convert.class)
                    .orShould().haveName("password") // Exception: Password uses BCrypt (Hashing), not @Convert
                    .because("Sensitive financial data must be encrypted at rest using the AttributeEncryptor.");

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
}