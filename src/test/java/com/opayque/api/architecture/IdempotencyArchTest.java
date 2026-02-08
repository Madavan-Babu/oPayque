package com.opayque.api.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/// **Story 3.2: Idempotency Architecture Guardrails.**
///
/// This suite enforces the "Explicit > Implicit" philosophy for the Idempotency mechanism.
/// It prevents the re-introduction of "Magic" (AOP) and ensures the Transfer Engine
/// remains the explicit orchestrator of transaction uniqueness.
@AnalyzeClasses(packages = "com.opayque.api", importOptions = ImportOption.DoNotIncludeTests.class)
public class IdempotencyArchTest {

    /// **Rule 1: The "No Magic" Mandate.**
    ///
    /// Explicitly forbids the creation of Aspect-Oriented Programming (AOP) Aspects
    /// within the idempotency package.
    ///
    /// **Rationale:** We chose an explicit `lock()/complete()` pattern to avoid the
    /// hidden complexity, serialization risks, and debugging difficulty of AOP-based
    /// caching headers.
    @ArchTest
    static final ArchRule idempotency_should_not_use_aop_magic = noClasses()
            .that().resideInAPackage("..idempotency..")
            .should().beAnnotatedWith("org.aspectj.lang.annotation.Aspect")
            .because("Story 3.2 mandates explicit idempotency checks. AOP Aspects are strictly forbidden to ensure code transparency.");

    /// **Rule 2: The "Hard-Wired" Integration Check.**
    ///
    /// Verifies that the `TransferService` has a direct, explicit dependency on the
    /// `IdempotencyService`.
    ///
    /// **Rationale:** If `TransferService` does not depend on `IdempotencyService`,
    /// it implies that the "Safety Net" has been disconnected or bypassed.
    /// This rule acts as a compile-time guarantee that the wiring exists.
    @ArchTest
    static final ArchRule transfer_service_must_use_explicit_idempotency = classes()
            .that().haveSimpleName("TransferService")
            .should().dependOnClassesThat().haveSimpleName("IdempotencyService")
            .because("The Transfer Engine must explicitly orchestrate the Idempotency Lock. Implicit/Missing wiring is a safety violation.");

    /// **Rule 3: Layering Protection (Improvised).**
    ///
    /// Prevents `Controllers` from bypassing the Service layer and talking directly to Redis.
    ///
    /// **Rationale:** Idempotency is a Business Logic concern (locking the Transaction ID),
    /// not just an HTTP concern. It must be handled inside the Service Transaction boundary
    /// (or immediately preceding it), not in the web layer.
    @ArchTest
    static final ArchRule controllers_must_not_touch_idempotency_directly = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().haveSimpleName("IdempotencyService")
            .because("Idempotency is a business consistency mechanism. Controllers should delegate this to the Service Layer.");
}