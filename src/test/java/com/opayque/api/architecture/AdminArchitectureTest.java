package com.opayque.api.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

@AnalyzeClasses(packages = "com.opayque.api", importOptions = ImportOption.DoNotIncludeTests.class)
@SuppressWarnings("unused")
public class AdminArchitectureTest {

    // =========================================================================
    // GUARDRAIL 1: LAYERED INTEGRITY
    // =========================================================================

    /**
     * Rule: Admin Controllers must NEVER talk to Repositories directly.
     * They must go through the Service layer.
     * <p>
     * Violation Example: Injecting AccountRepository into AdminWalletController.
     */
    @ArchTest
    static final ArchRule admin_controllers_should_not_access_repositories =
            noClasses()
                    .that().resideInAPackage("..admin.controller..")
                    .should().dependOnClassesThat().resideInAPackage("..repository..");

    // =========================================================================
    // GUARDRAIL 2: DATA ISOLATION (The "Leak" Check)
    // =========================================================================

    /**
     * Rule: Admin DTOs are strictly for Admin use.
     * <p>
     * Violation Example: Using AccountStatusUpdateRequest in the
     * public-facing WalletController (com.opayque.api.wallet..).
     */
    @ArchTest
    static final ArchRule admin_dtos_should_be_isolated =
            classes()
                    .that().resideInAPackage("..admin.dto..")
                    .should().onlyBeAccessed().byClassesThat()
                    .resideInAnyPackage(
                            "..admin..",               // The Admin Domain itself
                            "..infrastructure..",      // JSON Serializers / Error Handling
                            "java.."                   // Java internal reflection
                    );

    // =========================================================================
    // GUARDRAIL 3: SECURITY ENFORCEMENT (The "Suspenders" Check)
    // =========================================================================

    /**
     * Rule: Defense in Depth.
     * Every public method in an Admin Controller MUST be explicitly secured
     * with @PreAuthorize.
     * <p>
     * Why: Even if SecurityConfig (Network Layer) has a typo, this Application Layer
     * check ensures the door is locked.
     */
    @ArchTest
    static final ArchRule admin_endpoints_must_be_secured =
            methods()
                    .that().arePublic()
                    .and().areDeclaredInClassesThat().resideInAPackage("..admin.controller..")
                    .and().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                    .should().beAnnotatedWith(PreAuthorize.class);

    // =========================================================================
    // GUARDRAIL 4: NAMING CONVENTION
    // =========================================================================

    /**
     * Rule: Clarity.
     * Classes in the admin controller package must end with 'Controller'.
     */
    @ArchTest
    static final ArchRule admin_controllers_should_be_named_correctly =
            classes()
                    .that().resideInAPackage("..admin.controller..")
                    .should().haveSimpleNameEndingWith("Controller");
}