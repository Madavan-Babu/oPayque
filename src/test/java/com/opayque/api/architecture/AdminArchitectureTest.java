package com.opayque.api.architecture;

import com.opayque.api.identity.controller.AdminController;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

/**
 * Architecture tests for the Admin domain.
 * <p>
 * This class defines a suite of ArchUnit rules that enforce layered
 * integrity, data isolation, security enforcement, and naming conventions
 * within the Admin package. By codifying these guardrails, the architecture
 * prevents Admin Controllers from accessing Repositories directly,
 * restricts Admin DTOs to the Admin domain, requires every public
 * Admin Controller method to be secured with {@code @PreAuthorize},
 * and ensures a consistent “*Controller” naming pattern for clarity.
 *
 * @author Madavan Babu
 * @since 2026
 *
 * @see ArchTest
 * @see ArchRule
 * @see RestController
 * @see PreAuthorize
 */
@AnalyzeClasses(packages = "com.opayque.api", importOptions = ImportOption.DoNotIncludeTests.class)
@SuppressWarnings("unused")
public class AdminArchitectureTest {

    // =========================================================================
    // GUARDRAIL 1: LAYERED INTEGRITY
    // =========================================================================

    /**
     * <p>{@code admin_controllers_should_not_access_repositories} defines an ArchUnit rule that
     * forbids any class residing in an {@code ..admin.controller..} package from depending on
     * classes located in an {@code ..repository..} package.</p>
     *
     * <p><strong>Purpose:</strong> Enforcing strict layer boundaries keeps the Administration
     * UI layer focused on request handling and delegating business logic to the Service layer.
     * Direct repository access from Controllers would blur responsibilities, introduce
     * persistence concerns into the web tier, and hinder isolated testing.</p>
     *
     * @see AdminController
     * @see com.opayque.api.admin.service.AdminWalletService
     * @see com.opayque.api.admin.controller.AdminWalletController
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
     * <p>This architectural rule guarantees that Data Transfer Objects (DTOs) belonging to the
     * <em>admin</em> domain remain isolated from all other code bases.</p>
     *
     * <p>It selects every class that resides in a package matching {@code ..admin.dto..}
     * and restricts access so that only classes located in one of the following packages
     * may reference them:</p>
     *
     * <ul>
     *   <li>{@code ..admin..} – the admin domain itself.</li>
     *   <li>{@code ..infrastructure..} – components such as JSON serializers and error‑handling utilities.</li>
     *   <li>{@code java..} – core Java packages (e.g., reflection utilities).</li>
     * </ul>
     *
     * <p>Enforcing this rule prevents accidental coupling between the admin layer and unrelated
     * business modules, thereby reducing the risk of leaking internal admin data structures
     * and preserving a clean, maintainable architecture.</p>
     *
     * @author Madavan Babu
     * @since 2026
     *
     * @see AdminArchitectureTest
     * @see ArchTest
     * @see ArchRule
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
     * <p>Ensures that every public method declared in a class residing in the
     * {@code ..admin.controller..} package and annotated with
     * {@link RestController} is also
     * annotated with {@link PreAuthorize}.</p>
     *
     * <p>This rule enforces a security boundary for administrative HTTP endpoints.
     * By mandating the presence of {@code @PreAuthorize}, it guarantees that
     * access‑control policies are explicitly defined at the controller layer,
     * preventing accidental exposure of privileged operations and supporting the
     * “Secure Gateway” architectural pattern.</p>
     *
     * @see AdminArchitectureTest
     * @see RestController
     * @see PreAuthorize
     *
     * @author Madavan Babu
     * @since 2026
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
     * <p>Ensures that every class residing in an <em>admin</em> controller package follows the
     * conventional naming pattern ending with {@code "Controller"}.</p>
     *
     * <p><strong>Purpose:</strong> Enforcing a consistent naming scheme makes it trivial to
     * identify administrative entry points in the codebase and supports automated tooling
     * (e.g., API documentation generators, security scanners) that rely on the suffix to
     * apply admin‑specific cross‑cutting concerns such as audit logging or role‑based access.</p>
     *
     * <p>This rule targets classes that match the package pattern {@code "..admin.controller.."}.
     * Any deviation—such as {@code AdminUser} or {@code AdminFacade} placed in that package—will
     * cause the ArchUnit test to fail, prompting developers to rename the class appropriately
     * (e.g., {@code AdminUserController}).</p>
     *
     * @see AdminArchitectureTest
     * @see ArchRule
     */
    @ArchTest
    static final ArchRule admin_controllers_should_be_named_correctly =
            classes()
                    .that().resideInAPackage("..admin.controller..")
                    .should().haveSimpleNameEndingWith("Controller");
}