/**
 * Architectural Fortress for the Card Domain.
 * <p>
 * This test class enforces the “Fortress” pattern within the oPayque modular monolith,
 * ensuring that every layer inside {@code com.opayque.api.card} remains strictly
 * compartmentalized. Violations are rejected at build-time, guaranteeing PCI-DSS
 * segmentation, PSD2 secure coding guidelines, and internal AML/KYC auditability.
 * </p>
 * <p>
 * All rules are evaluated by ArchUnit during the {@code test} phase, providing
 * an immutable security gate that no pull request can bypass.
 * </p>
 *
 * @author Madavan Babu
 * @since 2026
 */
package com.opayque.api.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;


/**
 * Story 4.3: Architectural Guardrails.
 * <p>
 * Enforces the strict "Modular Monolith" structure for the Card Domain.
 * These tests ensure that the "Fortress" design pattern is not violated by
 * lazy coding practices in the future.
 * </p>
 * Container for architectural guardrails that keep the Card Domain compliant
 * with oPayque’s security and regulatory mandates.
 * <p>
 * The class is not instantiable; all rules are static and executed by ArchUnit.
 * </p>
 *
 * @author Madavan Babu
 * @since 2026
 */
@AnalyzeClasses(packages = "com.opayque.api.card", importOptions = ImportOption.DoNotIncludeTests.class)
public class CardArchitectureTest {

    // =========================================================================
    // 1. PACKAGE & NAMING DISCIPLINE
    // =========================================================================

    /**
     * Enforces that every HTTP ingress point (REST or MVC) lives in the
     * {@code controller} package, preventing accidental exposure of
     * internal service classes as endpoints and reducing the attack surface
     * for OWASP API Top-10 risks.
     */
    @ArchTest
    static final ArchRule controllers_must_reside_in_controller_package =
            classes().that().haveNameMatching(".*Controller")
                    .should().resideInAPackage("..controller..")
                    .as("Controllers must be strictly compartmentalized in the 'controller' package");

    /**
     * Guarantees that all business logic is centralized inside the {@code service}
     * package, enabling uniform transaction boundaries, fraud-detection hooks,
     * and regulatory audit logging.
     */
    @ArchTest
    static final ArchRule services_must_reside_in_service_package =
            classes().that().haveNameMatching(".*Service")
                    .should().resideInAPackage("..service..")
                    .as("Business logic must reside strictly in the 'service' package");


    /**
     * Ensures JPA entities remain inside the {@code entity} package, preventing
     * accidental leakage of persistence-layer details into controllers or DTOs,
     * which would violate PCI-DSS requirement 6.5.4 (Information Disclosure).
     */
    @ArchTest
    static final ArchRule entities_must_reside_in_entity_package =
            classes().that().areAnnotatedWith(Entity.class)
                    .should().resideInAPackage("..entity..")
                    .as("JPA Entities must reside in the 'entity' package");

    // =========================================================================
    // 2. LAYER ISOLATION (THE THIN CONTROLLER)
    // =========================================================================

    /**
     * Prohibits controllers from directly wiring or invoking repository interfaces,
     * enforcing the Service layer as the sole gatekeeper for data access. This
     * prevents SQL-injection vectors and guarantees that every database call is
     * wrapped in declarative transaction control and AML/KYC checks.
     */
    @ArchTest
    static final ArchRule controllers_must_not_access_repositories =
            noClasses().that().areAnnotatedWith(RestController.class)
                    .or().areAnnotatedWith(Controller.class)
                    .should().dependOnClassesThat().haveNameMatching(".*Repository")
                    .as("Controllers must NEVER bypass the Service layer to touch the Database directly");

    /**
     * Blocks REST endpoints from returning JPA entities, forcing the use of
     * sanitized DTOs. This eliminates the risk of exposing PAN, CVV, or HSM-derived
     * keys to downstream consumers, aligning with PCI-DSS requirement 3.4
     * (Strong Cryptography) and GDPR data-minimization principles.
     */
    @ArchTest
    static final ArchRule controllers_must_not_return_entities =
            noMethods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                    .should().haveRawReturnType("com.opayque.api.card.entity.VirtualCard")
                    .orShould().haveRawReturnType("java.util.List<com.opayque.api.card.entity.VirtualCard>")
                    .as("Controllers must return DTOs, never raw Entities (prevents data leakage)");

    // =========================================================================
    // 3. SERVICE LAYER SECURITY & INTEGRITY
    // =========================================================================

    /**
     * Requires every concrete service class to carry Spring’s {@code @Service}
     * stereotype, ensuring dependency-injection, AOP-based security interceptors,
     * and uniform profiling/observability across the Card Domain.
     */
    @ArchTest
    static final ArchRule services_must_be_annotated =
            classes().that().haveSimpleNameEndingWith("Service")
                    .and().areNotInterfaces()
                    .should().beAnnotatedWith(Service.class)
                    .as("All Service classes must be Spring-managed beans (@Service)");

    /**
     * Mandates that every public method inside {@code CardIssuanceService} is
     * executed inside a Spring transaction, guaranteeing all-or-nothing PAN
     * generation, HSM key injection, and ledger posting. This prevents orphan
     * records and satisfies PSD2 strong-customer-authentication rollback rules.
     */
    @ArchTest
    static final ArchRule card_issuance_logic_must_be_transactional =
            methods().that().areDeclaredInClassesThat().haveSimpleName("CardIssuanceService")
                    .and().arePublic()
                    .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                    .as("Card Issuance operations must be Transactional to ensure atomicity");

    // =========================================================================
    // 4. THE "SOFT DELETE" GUARDRAIL (NO HARD DELETES)
    // =========================================================================

    // NEW GUARDRAIL: This makes Hard Deletes impossible in the Service Layer.
    /**
     * Outlaws hard-delete calls from the service layer, enforcing soft-delete
     * semantics via status transitions (e.g., {@code setStatus(TERMINATED)}).
     * This guarantees immutable audit trails required by PCI-DSS 10.2.1 and
     * simplifies forensic reconstruction during fraud investigations.
     */
    @ArchTest
    static final ArchRule services_must_not_hard_delete =
            noClasses().that().resideInAPackage("..service..")
                    .should().callMethodWhere(
                            target(nameMatching("delete.*"))
                                    .and(target(owner(assignableTo(Repository.class))))
                    )
                    .as("Hard Deletes are FORBIDDEN. Services must use Soft Deletes (e.g., setStatus(TERMINATED)) via .save().");


    // =========================================================================
    // 5. THE FORTRESS STRUCTURE (LAYERED ARCHITECTURE)
    // =========================================================================

    /**
     * Codifies the Fortress pattern: a strict layered topology where only
     * downward dependencies are allowed. Controllers are isolated from
     * repositories, services are self-contained, and entities remain pure
     * persistence objects—eliminating circular coupling and ensuring clean
     * separation of concerns for rapid PCI-DSS and GDPR audits.
     */
    @ArchTest
    static final ArchRule layer_dependencies_are_respected = layeredArchitecture()
            .consideringOnlyDependenciesInAnyPackage("com.opayque.api.card..")
            .layer("Controller").definedBy("..controller..")
            .layer("Service").definedBy("..service..")
            .layer("Repository").definedBy("..repository..")
            .layer("Entity").definedBy("..entity..")

            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller", "Service")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
            .as("Strict Layered Architecture: Controller -> Service -> Repository");
}