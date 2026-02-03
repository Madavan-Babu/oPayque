package com.opayque.api.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/// Multi-Currency Account Management - Architectural Governance.
///
/// This suite utilizes ArchUnit to programmatically enforce the structural integrity
/// of the oPayque "Opaque" architecture. It ensures that the critical financial core
/// (Wallet Domain) remains isolated, follows strict layering, and adheres to
/// mandatory banking-grade precision standards.
@AnalyzeClasses(packages = "com.opayque.api", importOptions = ImportOption.DoNotIncludeTests.class)
public class WalletArchitectureTest {

    /// Pillar 1: Domain Isolation.
    ///
    /// Protects the Wallet domain from external identity logic. While shared entities
    /// and infrastructure utilities are permitted for monolithic pragmatism, the
    /// domain must never depend on the Identity web layer (Controllers/Services)
    /// to prevent cyclic dependencies and leakages.
    @ArchTest
    static final ArchRule wallet_should_not_depend_on_identity_web_layer = classes()
            .that().resideInAPackage("..wallet..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "..wallet..",           // Domain internal
                    "..identity.entity..",  // Authorized shared entities
                    "..identity.repository..", // Authorized shared persistence
                    "..infrastructure..",   // Cross-cutting concerns
                    "..integration..",
                    "..common..",           // Shared utilities (@Masked)
                    "java..", "javax..", "jakarta..", // Standard Java
                    "org.springframework..", "org.slf4j..", "lombok..", // Framework
                    "net.jqwik..", "org.junit..", // Testing

                    // --- NEW ALLOWED DEPENDENCIES ---
                    "org.joda.money..",           // Approved Financial Library (Story 2.1)
                    "org.hibernate.annotations..",// Approved Persistence Annotations (@Immutable)
                    "io.github.resilience4j..",    // Approved Circuit Breaker (Story 2.3)
                    "org.hibernate.proxy.." // THE FIX: Allow Hibernate Proxies (ByteBuddy)
            ).because("The Wallet Domain must be isolated from Identity web logic, but allowed to use approved infrastructure libraries.");

    /// Pillar 2: Layered Architecture Enforcement.
    ///
    /// Mandates a strict unidirectional flow: Controller -> Service -> Repository.
    /// This prevents "Shortcut Anti-patterns" such as direct repository access from
    /// controllers, ensuring that business rules in the service layer are never bypassed.
    @ArchTest
    static final ArchRule layered_architecture_is_respected = Architectures.layeredArchitecture()
            .consideringOnlyDependenciesInAnyPackage("com.opayque.api.wallet..")
            .layer("Controller").definedBy("..controller..")
            .layer("Service").definedBy("..service..")
            .layer("Repository").definedBy("..repository..")
            .layer("Entity").definedBy("..entity..")

            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service");

    /// Pillar 3: Cyclic Dependency Detection.
    ///
    /// Performs slice analysis to prevent circular references within the wallet package.
    /// Eliminating cycles is critical for maintainability and ensures that components
    /// can be independently tested or refactored.
    @ArchTest
    static final ArchRule wallet_should_be_free_of_cycles = slices()
            .matching("com.opayque.api.wallet.(*)..")
            .should().beFreeOfCycles();

    /// Pillar 4: Naming Conventions for Discovery.
    ///
    /// Standardizes component naming to improve project navigability.
    /// All business logic implementations must carry the 'Service' suffix.
    @ArchTest
    static final ArchRule services_should_be_named_correctly = classes()
            .that().areAnnotatedWith(Service.class)
            .should().haveSimpleNameEndingWith("Service");

    /// Pillar 5: Annotation Integrity.
    ///
    /// Ensures that all API entry points in the wallet domain are correctly
    /// defined as @RestController to maintain consistent JSON response standards.
    @ArchTest
    static final ArchRule controllers_must_be_rest_controllers = classes()
            .that().haveSimpleNameEndingWith("Controller")
            .and().resideInAPackage("..wallet..")
            .should().beAnnotatedWith(RestController.class);

    /// Pillar 6: Financial Precision Guardrail (Story 2.1 Mandate).
    ///
    /// Prohibits the use of 'Double' or 'Float' for monetary data types.
    /// These types utilize binary floating-point representation, which leads to
    /// catastrophic rounding errors in a banking context.
    ///
    /// Governance: All monetary values must utilize BigDecimal or Joda-Money.
    @ArchTest
    static final ArchRule no_floating_point_money = noClasses()
            .that().resideInAPackage("..wallet..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.Double")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Float")
            .because("Financial integrity requires exact precision. Use BigDecimal or Joda-Money to prevent lost cents.");

    /// Pillar 7: DTO Location Governance.
    ///
    /// Prevents "Inner Class Pollution" where Data Transfer Objects (DTOs) are
    /// lazily defined inside Controllers or Services.
    /// Strictly enforces that all API contracts (Requests, Responses, Summaries)
    /// reside in the dedicated 'dto' package for reusability and documentation.
    @ArchTest
    static final ArchRule dtos_must_reside_in_dto_package = classes()
            .that().haveSimpleNameEndingWith("Request")
            .or().haveSimpleNameEndingWith("Response")
            .or().haveSimpleNameEndingWith("Summary")
            .should().resideInAPackage("..dto..");
}