package com.opayque.api.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/// oPayque Structural Guardrails - Redis Isolation Testing
///
/// This suite programmatically enforces the separation between infrastructure caching
/// and the high-level business logic. It ensures that Redis dependencies do not leak
/// into the presentation or domain layers.
@AnalyzeClasses(packages = "com.opayque.api", importOptions = ImportOption.DoNotIncludeTests.class)
public class RedisArchitectureTest {

    /// The Presentation Layer (Controllers) must NEVER talk to Redis directly.
    ///
    /// Access must be proxied through the Service Layer or Security Filters to
    /// maintain a clean, testable API surface.
    @ArchTest
    static final ArchRule controllers_should_not_use_redis = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.data.redis..");

    /// The Domain Layer (Entities) must remain pure and database-agnostic.
    ///
    /// This rule ensures that our JPA entities remain focused on the **PostgreSQL** /// ledger and are not coupled to the **Story 1.4: Kill Switch** implementation.
    @ArchTest
    static final ArchRule domain_should_not_use_redis = noClasses()
            .that().resideInAPackage("..identity.entity..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.data.redis..");
}