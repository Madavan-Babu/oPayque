package com.opayque.api.architecture;

import com.opayque.api.identity.service.JwtService;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.servlet.http.HttpServletRequest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/// oPayque Security Guardrails - Domain Isolation Testing
///
/// This suite utilizes ArchUnit to programmatically enforce the "Opaque" security model.
/// It ensures that cryptographic logic (JWT) and low-level HTTP details are strictly
/// encapsulated within their designated domains to prevent lateral architectural drift.
///
/// ### Core Rules:
/// - **JwtService Isolation**: Restricts token handling to identity services and security filters.
/// - **Controller Purity**: Ensures REST controllers remain decoupled from the underlying Jakarta Servlet API.
/// - **Library Encapsulation**: Guarantees that the Auth0 JWT library is strictly an implementation detail of the Identity domain.
@AnalyzeClasses(
        packages = "com.opayque.api",
        importOptions = ImportOption.DoNotIncludeTests.class // <--- THIS LINE IS CRITICAL
)
class SecurityIsolationTest {

    /// Ensures that the {@link JwtService} is only utilized by the identity domain.
    ///
    /// This prevents other domains (like Wallet or Transactions) from attempting
    /// to manually parse or validate tokens, maintaining a single source of truth
    /// for security logic.
    @ArchTest
    static final ArchRule jwt_service_access_is_restricted = classes()
            .that().haveSimpleName("JwtService")
            .should().onlyBeAccessed().byAnyPackage(
                    "..identity.service..",
                    "..identity.security.."
            );

    /// Enforces clean MVC boundaries by forbidding raw {@link HttpServletRequest}
    /// access in Controllers.
    ///
    /// This ensures that controllers rely on Spring's abstraction (DTOs and Params)
    /// rather than poking into low-level request data, which enhances testability.
    @ArchTest
    static final ArchRule controllers_should_not_access_http_servlet_request = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().haveFullyQualifiedName("jakarta.servlet.http.HttpServletRequest");

    /// Hardens the cryptographic perimeter by ensuring that the Auth0 JWT library
    /// is never used outside the Identity domain.
    ///
    /// This ensures that if we ever swap JWT libraries, the impact is isolated
    /// to a single package, preserving the "Opaque" architecture.
    @ArchTest
    static final ArchRule identity_domain_is_the_only_token_signer = noClasses()
            .that().resideOutsideOfPackage("..identity..")
            .should().dependOnClassesThat().resideInAPackage("com.auth0.jwt..");
}