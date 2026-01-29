package com.opayque.api.architecture;

import com.opayque.api.infrastructure.util.SecurityUtil;
import com.opayque.api.identity.security.JwtAuthenticationFilter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.security.core.context.SecurityContextHolder;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/// oPayque Structural Guardrails - Security Isolation Testing
///
/// This suite programmatically enforces the "The Least Privilege" access model for the
/// Spring Security Context. It ensures that sensitive thread-local identity data is
/// only accessed through authorized gateways.
///
/// ### BOLA Prevention Strategy:
/// - **Controlled Write**: Only the {@link JwtAuthenticationFilter} can populate the context.
/// - **Controlled Read**: Only {@link SecurityUtil} can extract the identity.
/// - **Forbidden Direct Access**: Prevents "Identity Leaks" or manual tampering within
///   business services or repositories.
@AnalyzeClasses(packages = "com.opayque.api", importOptions = ImportOption.DoNotIncludeTests.class)
class SecurityArchitectureTest {

    /// Ensures that no class outside of the designated security infrastructure
    /// depends on {@link SecurityContextHolder}.
    ///
    /// This rule is critical for preventing BOLA vulnerabilities by forcing all
    /// developers to use our audited {@link SecurityUtil} for identity lookups.
    @ArchTest
    static final ArchRule security_context_should_be_isolated = noClasses()
            .that().doNotHaveFullyQualifiedName("com.opayque.api.identity.security.JwtAuthenticationFilter")
            .and().doNotHaveFullyQualifiedName("com.opayque.api.infrastructure.util.SecurityUtil")
            .should().dependOnClassesThat().belongToAnyOf(SecurityContextHolder.class)
            .because("Direct access to SecurityContextHolder is forbidden; use SecurityUtil for identity lookups to prevent BOLA vulnerabilities.");
}