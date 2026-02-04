package com.opayque.api.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Service;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/// Epic 3: Atomic Transaction Engine - Architectural Governance.
///
/// Enforces strict orchestration rules for the Transaction Domain.
/// Since this domain coordinates money movement, it must adhere to strict
/// layering to prevent "Spaghetti Code" and ensure all transfers pass through
/// the proper locking mechanisms defined in the Wallet Domain.
@AnalyzeClasses(packages = "com.opayque.api", importOptions = ImportOption.DoNotIncludeTests.class)
public class TransactionArchitectureTest {

    /// Pillar 1: Orchestration Integrity (The "Anti-Bypass" Rule).
    ///
    /// The Transaction Service orchestrates high-level business logic.
    /// It MUST NOT bypass the 'Wallet' or 'Identity' service layers to access
    /// their databases directly. Doing so would bypass critical PESSIMISTIC_WRITE locks.
    @ArchTest
    static final ArchRule transactions_must_use_services_not_repositories = noClasses()
            .that().resideInAPackage("..transactions..")
            .should().dependOnClassesThat().resideInAPackage("..wallet.repository..")
            .orShould().dependOnClassesThat().resideInAPackage("..identity.repository..")
            .because("The Transaction Engine must orchestrate via Services to ensure locks are never bypassed.");

    /// Pillar 2: Standard Layered Architecture.
    @ArchTest
    static final ArchRule standard_layered_architecture = classes()
            .that().resideInAPackage("..transactions.controller..")
            .should().dependOnClassesThat().resideInAPackage("..transactions.service..")
            .andShould().onlyHaveDependentClassesThat().resideInAPackage("..transactions.controller..")
            .because("Controllers should delegate strictly to Services.");

    /// Pillar 3: Financial Precision Guardrail.
    @ArchTest
    static final ArchRule no_floating_point_money_in_transactions = noClasses()
            .that().resideInAPackage("..transactions..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.Double")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Float")
            .because("Financial integrity requires exact precision. Use BigDecimal.");

    /// Pillar 4: Service Transactionality (Strict Mode).
    ///
    /// We enforce @Transactional at the CLASS level.
    /// This is safer than method-level annotations for the Transfer domain because
    /// it guarantees that ANY new public method added to this service in the future
    /// will automatically be atomic.
    @ArchTest
    static final ArchRule transaction_services_must_be_transactional = classes()
            .that().resideInAPackage("..transactions.service..")
            .and().areAnnotatedWith(Service.class)
            .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional")
            .because("Transfers involve multiple write operations. Class-level atomicity is required for safety.");
}