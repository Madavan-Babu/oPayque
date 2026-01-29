package com.opayque.api.architecture;

import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;

/// Epic 1: Security Hardening - Automated Privacy Governance.
///
/// This suite enforces "Privacy by Design" at the bytecode level using ArchUnit.
/// It functions as a static analysis guardrail to prevent PII exposure via
/// standard streams or unmasked response DTOs.
///
/// Compliance: Aligns with OWASP API Security Top 10 regarding Data Exposure.
@AnalyzeClasses(packages = "com.opayque.api", importOptions = ImportOption.DoNotIncludeTests.class)
public class PrivacyArchitectureTest {

    /// Rule 1: Standard Stream Access Prohibition.
    ///
    /// Forbids usage of System.out or System.err. All telemetry must utilize
    /// the SLF4J logging abstraction to ensure consistent log sanitization.
    @ArchTest
    static final ArchRule no_standard_streams = noClasses()
            .should(ACCESS_STANDARD_STREAMS)
            .because("System.out/err bypasses centralized log masking. Use Slf4j logger instead.");

    /// Rule 2: Mandatory Masking for Egress DTOs.
    ///
    /// Identifies potential PII fields in Response objects via regex.
    /// Fields matching sensitive patterns (email, ssn, iban, etc.) must be
    /// annotated with @Masked to prevent data leaks.
    @ArchTest
    static final ArchRule sensitive_fields_must_be_masked = fields()
            .that().areDeclaredInClassesThat().resideInAPackage("..dto..")
            .and().areDeclaredInClassesThat().haveSimpleNameEndingWith("Response")
            .and().haveNameMatching("(?i).*(email|fullName|iban|password|ssn).*")
            .should(beAnnotatedWithMasked())
            .because("Sensitive fields in Response DTOs must be explicitly protected via @Masked.");

    /// Custom condition to verify the presence of masking annotations.
    private static ArchCondition<JavaField> beAnnotatedWithMasked() {
        return new ArchCondition<>("be annotated with @Masked") {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                // Verify presence of custom @Masked or standard Jackson serialization overrides.
                boolean isMasked = field.getAnnotations().stream()
                        .anyMatch(a -> a.getRawType().getSimpleName().equals("Masked"));

                boolean isJacksonMasked = field.getAnnotations().stream()
                        .anyMatch(a -> a.getRawType().getSimpleName().equals("JsonSerialize"));

                if (!isMasked && !isJacksonMasked) {
                    String message = String.format(
                            "Field '%s' in class '%s' is potentially sensitive but missing @Masked annotation.",
                            field.getName(), field.getOwner().getName());
                    events.add(SimpleConditionEvent.violated(field, message));
                }
            }
        };
    }
}