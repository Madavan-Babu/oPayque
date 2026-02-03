package com.opayque.api.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/// Double-Entry Ledger Engine - Architectural Governance.
///
/// This specific suite governs the "Physics" of the banking ledger.
/// It enforces immutability, performance optimizations, and strict isolation
/// of the external world from the core financial data structures.
@AnalyzeClasses(packages = "com.opayque.api", importOptions = ImportOption.DoNotIncludeTests.class)
public class LedgerArchitectureTest {

    /// Pillar 1: Immutability & Performance Optimization.
    ///
    /// The Ledger is an "Append-Only" log. Historical data should never change.
    /// We enforce the use of Hibernate's @Immutable annotation.
    ///
    /// Why? This tells the Hibernate Dirty Checking mechanism to IGNORE these instances
    /// after they are loaded. This saves massive CPU/Memory when processing thousands
    /// of historical ledger rows, as the ORM doesn't need to track them for updates.
    @ArchTest
    static final ArchRule ledger_entries_must_be_hibernate_immutable = classes()
            .that().haveSimpleName("LedgerEntry")
            .should().beAnnotatedWith(org.hibernate.annotations.Immutable.class)
            .because("Ledger entries are append-only. Hibernate dirty-checking wastes CPU resources on historical data.");

    /// Pillar 2: Core Domain Isolation (The "Air Gap").
    ///
    /// The Core Wallet Entities and Repositories must NEVER know about external APIs.
    /// Only the 'Service' layer (the Orchestrator) is allowed to talk to the 'Integration' layer.
    ///
    /// This prevents "leaky abstractions" where an Exchange Rate API object accidentally
    /// ends up inside a persistence entity or a database query.
    @ArchTest
    static final ArchRule ledger_must_not_depend_on_external_apis = noClasses()
            .that().resideInAPackage("..wallet..")
            .and().resideOutsideOfPackage("..wallet.service..") // Services are the only allowed gateway
            .should().dependOnClassesThat()
            .resideInAPackage("..integration..")
            .because("The Domain (Entities/Repos) must remain pure. Only Services can orchestrate external integrations.");

    /// Pillar 3: Financial Precision Guardrail.
    ///
    /// Reinforcing the Story 2.1 Mandate specifically for the Ledger components.
    /// Floating point math (Double/Float) is strictly forbidden in the ledger.
    @ArchTest
    static final ArchRule financial_precision_check = noClasses()
            .that().resideInAPackage("..wallet..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.Double")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Float")
            .because("Financial integrity requires exact precision. Use BigDecimal or Joda-Money to prevent lost cents.");

}