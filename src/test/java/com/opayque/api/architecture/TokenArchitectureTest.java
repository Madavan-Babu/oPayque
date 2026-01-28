package com.opayque.api.architecture;

import com.opayque.api.identity.entity.RefreshToken;
import com.opayque.api.identity.repository.RefreshTokenRepository;
import com.opayque.api.identity.service.AuthService;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/// Epic 1: Identity & Access Management - Token Architecture Governance.
///
/// This suite enforces the "Opaque" security constraints and architectural boundaries
/// for user session tokens. It utilizes ArchUnit to programmatically ensure that
/// sensitive persistence entities and repositories remain isolated within the
/// authorized layers, preventing architectural leakage and side-channel vulnerabilities.
@AnalyzeClasses(packages = "com.opayque.api", importOptions = ImportOption.DoNotIncludeTests.class)
public class TokenArchitectureTest {

    /// Rule 1: The "Vault Secret" Protection.
    ///
    /// The [RefreshToken] entity is a critical security asset containing session data.
    /// This rule mandates that it strictly resides within the entity package and is
    /// accessible only by the persistence, repository, and service layers.
    /// Access by the Controller or public API layers is strictly prohibited.
    @ArchTest
    static final ArchRule refresh_token_entity_should_be_protected = classes()
            .that().haveSimpleName("RefreshToken")
            .should().onlyBeAccessed().byClassesThat()
            .resideInAnyPackage(
                    "..entity..",       // Internal package access
                    "..repository..",   // Direct manager access
                    "..service.."       // Orchestrator access
            )
            .because("The RefreshToken entity is a critical security asset and must not leak to Controllers or API layers.");

    /// Rule 2: The "Vault Key" Isolation.
    ///
    /// Ensures that the [RefreshTokenRepository] is isolated from unauthorized service
    /// or controller injection. By restricting access exclusively to the [AuthService],
    /// the system enforces centralized lifecycle management of refresh tokens and
    /// mitigates accidental session manipulation.
    @ArchTest
    static final ArchRule refresh_token_repository_isolation = noClasses()
            .that().haveSimpleNameNotEndingWith("AuthService") // Exclude authorized orchestrator
            .and().haveSimpleNameNotEndingWith("RefreshTokenRepository") // Exclude internal access
            .should().dependOnClassesThat().haveSimpleName("RefreshTokenRepository")
            .because("Centralized Control: Only AuthService is authorized to manage the lifecycle of Refresh Tokens.");

    /// Rule 3: The "Opaque Token" Mandate.
    ///
    /// Verifies that Refresh Tokens are treated as persistent identity entities
    /// rather than stateless claims. This rule prevents architectural confusion
    /// between opaque database-backed tokens and stateless JWT utilities.
    @ArchTest
    static final ArchRule token_logic_location = classes()
            .that().haveSimpleName("RefreshToken")
            .should().resideInAPackage("..identity.entity..")
            .because("Refresh Tokens are core Identity Entities, not generic utility tokens.");
}