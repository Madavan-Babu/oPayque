package com.opayque.api.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/// **Story 3.3: Transfer API Architecture Guardrails.**
///
/// This suite enforces the "Secure Gateway" pattern for the external-facing API.
/// It acts as a compile-time firewall, preventing common security mistakes like
/// Data Leakage (exposing DB entities) and Unguarded Endpoints (missing Rate Limiting).
@AnalyzeClasses(packages = "com.opayque.api", importOptions = ImportOption.DoNotIncludeTests.class)
public class TransferApiArchTest {

    /// **Rule 1: The "Data Leakage" Firewall.**
    ///
    /// Strictly forbids Controllers from having ANY dependency on JPA Entities.
    ///
    /// **Rationale:** If a Controller cannot see an Entity, it cannot accidentally return
    /// one in a JSON response (leaking password hashes or internal IDs). It forces
    /// the developer to use DTOs (Request/Response objects).
    @ArchTest
    static final ArchRule controllers_must_never_touch_entities = noClasses()
            .that().resideInAPackage("..transactions.controller..")
            .should().dependOnClassesThat().resideInAPackage("..entity..")
            .because("Controllers must never leak raw JPA Entities. You MUST map to DTOs to prevent internal schema exposure.");

    /// **Rule 2: The "Gatekeeper" Mandate.**
    ///
    /// Enforces that the `TransferController` has a hard dependency on the `RateLimiterService`.
    ///
    /// **Rationale:** The Transfer API is a high-risk endpoint for spam and DOS attacks.
    /// This rule ensures that the "Brakes" (Rate Limiter) are wired into the "Engine" (Controller).
    @ArchTest
    static final ArchRule transfer_controller_must_use_rate_limiter = classes()
            .that().haveSimpleName("TransferController")
            .should().dependOnClassesThat().haveSimpleName("RateLimiterService")
            .because("The Transfer API must be protected by the Rate Limiter. Missing dependency implies an unguarded gate.");

    /// **Rule 3: The "Distributed Lock" Enforcement.**
    ///
    /// Verification that the Rate Limiter is actually using Redis, not a local HashMap.
    ///
    /// **Rationale:** Local rate limiting fails in a clustered environment (Kubernetes).
    /// We strictly require `RedisTemplate` to ensure the counters are atomic and distributed.
    @ArchTest
    static final ArchRule rate_limiter_must_be_distributed = classes()
            .that().haveSimpleName("RateLimiterService")
            .should().dependOnClassesThat().haveSimpleName("RedisTemplate")
            .because("Rate Limiting must be distributed (Redis-backed) to support horizontal scaling. Local Maps are forbidden.");
}