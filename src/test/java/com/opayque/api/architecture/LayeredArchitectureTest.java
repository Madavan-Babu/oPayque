package com.opayque.api.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/// oPayque Structural Guardrails - Layered Isolation Testing
///
/// This suite programmatically enforces the "Opaque Architecture" principle.
/// It ensures that dependencies only flow in one direction, preventing circular
/// references and maintaining strict separation between the API web layer and the
/// persistence ledger.
@AnalyzeClasses(packages = "com.opayque.api", importOptions = ImportOption.DoNotIncludeTests.class)
class LayeredArchitectureTest {

    /// Enforces the core hierarchy of the oPayque ecosystem.
    ///
    /// ### Rules:
    /// - **Controller**: Entry point; must not be accessed by internal layers.
    /// - **Service**: Contains business logic; accessible only by Web and Security layers.
    /// - **Repository**: The "Data Shield"; accessible strictly by Services to ensure atomic transactions.
    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule layers_should_be_respected = layeredArchitecture()
            .consideringAllDependencies()
            .layer("Controller").definedBy("..controller..")
            .layer("Service").definedBy("..service..")
            .layer("Repository").definedBy("..repository..")
            .layer("Security").definedBy("..security..")

            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller", "Security")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service");
}