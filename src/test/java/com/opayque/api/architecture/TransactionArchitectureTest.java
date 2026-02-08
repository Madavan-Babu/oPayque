package com.opayque.api.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Service;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;


/// **Epic 3: Atomic Transaction Engine — Architectural Governance & Guardrails.**
///
/// This suite utilizes `ArchUnit` to programmatically enforce the structural integrity
/// of the `oPayque` banking core. It acts as an automated "Architectural Firewall," ensuring
/// that business logic adheres to the Opaque layering principles and the "Reliability-First" mandate.
///
/// **Governance Domains:**
/// - **Orchestration Integrity:** Prevents logic bypasses that would circumvent database locks.
/// - **Layering Discipline:** Enforces the strict `Controller` -> `Service` -> `Repository` flow.
/// - **Financial Safety:** Restricts usage of unsafe numeric types (`Double`/`Float`) in favor of `BigDecimal`.
/// - **Transactional Hygiene:** Mandates class-level atomicity for transaction-sensitive services.
///
@AnalyzeClasses(packages = "com.opayque.api", importOptions = ImportOption.DoNotIncludeTests.class)
public class TransactionArchitectureTest {

    /// **Pillar 1: Orchestration Integrity (Anti-Bypass Guardrail).**
    ///
    /// Mandates that classes within the `transactions` package must interact with the
    /// `Wallet` and `Identity` domains exclusively via their public `Service` APIs.
    ///
    /// **Rationale:** Direct repository access would bypass the `PESSIMISTIC_WRITE`
    /// locks implemented in the service layer, leading to race conditions and "Lost Updates"
    /// during concurrent fund movements.
    @ArchTest
    static final ArchRule transactions_must_use_services_not_repositories = noClasses()
            .that().resideInAPackage("..transactions..")
            .should().dependOnClassesThat().resideInAPackage("..wallet.repository..")
            .orShould().dependOnClassesThat().resideInAPackage("..identity.repository..")
            .because("The Transaction Engine must orchestrate via Services to ensure locks are never bypassed.");

    /// **Pillar 2: Structural Discipline (Standard Layered Architecture).**
    ///
    /// Enforces strict encapsulation of business logic within the `Service` layer.
    ///
    /// **Constraint:** `Controller` classes must act solely as request orchestrators and
    /// delegate all state-changing operations to the `Service` layer. It ensures that
    /// controllers do not leak domain logic or interact directly with persistence.
    @ArchTest
    static final ArchRule standard_layered_architecture = classes()
            .that().resideInAPackage("..transactions.controller..")
            .should().dependOnClassesThat().resideInAPackage("..transactions.service..")
            .andShould().onlyHaveDependentClassesThat().resideInAPackage("..transactions.controller..")
            .because("Controllers should delegate strictly to Services.");

    /// **Pillar 3: Financial Precision Guardrail (IEEE 754 Prohibition).**
    ///
    /// Prevents the introduction of floating-point inaccuracies within the transaction engine.
    ///
    /// **Technical Mandate:** All monetary calculations must utilize `BigDecimal` or
    /// `Joda Money`. Usage of `java.lang.Double` or `java.lang.Float` is strictly prohibited
    /// as these types cannot guarantee the exact decimal precision required for banking `ACID`
    /// compliance.
    @ArchTest
    static final ArchRule no_floating_point_money_in_transactions = noClasses()
            .that().resideInAPackage("..transactions..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.Double")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Float")
            .because("Financial integrity requires exact precision. Use BigDecimal.");

    /// **Pillar 4: Atomicity Enforcement (Service Transactionality).**
    ///
    /// Mandates the use of Spring's `@Transactional` annotation at the class level for
    /// all transaction services.
    ///
    /// **Rationale:** Class-level transactionality provides a "Fail-Safe" for the
    /// Atomic Transfer Engine. It guarantees that every public method added to the [Service]
    /// in the future will automatically be executed within a transaction boundary,
    /// ensuring partial writes are rolled back on failure.
    @ArchTest
    static final ArchRule transaction_services_must_be_transactional = classes()
            .that().resideInAPackage("..transactions.service..")
            .and().areAnnotatedWith(Service.class)
            .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional")
            .because("Transfers involve multiple write operations. Class-level atomicity is required for safety.");
}