package com.opayque.api.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import jakarta.persistence.Entity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

/**
 * Robust Architectural Guardrails for the Statement Domain.
 * <p>
 * These automated rules act as an invisible code reviewer, ensuring that
 * Domain-Driven Design constraints, Security logic, and Layered isolations
 * remain completely uncompromised in the future.
 *
 * @author Madavan Babu
 * @since 2026
 */
@AnalyzeClasses(packages = "com.opayque.api", importOptions = ImportOption.DoNotIncludeTests.class)
public class StatementArchitectureTest {

    // ==================================================================================
    // 1. LAYERED ARCHITECTURE INVARIANTS (THE BOUNDARIES)
    // ==================================================================================

    // Test 1: Controllers must not bypass Services to talk to Repositories
    @ArchTest
    static final ArchRule test1_ControllersMustNotAccessRepositories = noClasses()
            .that().resideInAPackage("..statement.controller..")
            .should().accessClassesThat().resideInAPackage("..repository..")
            .because("Controllers must delegate all data retrieval and business logic to the Service layer to ensure BOLA/RBAC checks execute.");

    // Test 2: Services must remain HTTP-Agnostic
    @ArchTest
    static final ArchRule test2_ServicesMustNotDependOnWebLayer = noClasses()
            .that().resideInAPackage("..statement.service..")
            .should().accessClassesThat().resideInAnyPackage("jakarta.servlet.http..", "org.springframework.web..")
            .because("The Service layer must remain protocol-agnostic. Use raw streams (like PrintWriter) instead of HttpServletResponse.");

    // Test 3: Domain Isolation (Prevent coupling to unauthorized domains)
    @ArchTest
    static final ArchRule test3_DomainIsolation = noClasses()
            .that().resideInAPackage("..statement..")
            .should().dependOnClassesThat().resideInAnyPackage("..admin..", "..card..", "..transactions..")
            .because("The Statement domain should only depend on shared infrastructure or its own allowed read-models (wallet/identity).");

    // ==================================================================================
    // 2. CONTROLLER GUARDRAILS (THE GATEWAYS)
    // ==================================================================================

    // Test 4: Controller Annotation Enforcement
    @ArchTest
    static final ArchRule test4_ControllersMustBeRestControllers = classes()
            .that().resideInAPackage("..statement.controller..")
            .and().haveSimpleNameEndingWith("Controller")
            .should().beAnnotatedWith(RestController.class)
            .andShould().beAnnotatedWith(RequestMapping.class)
            .because("All statement controllers must be strictly managed as REST endpoints.");


    // Test 5: Strict API Versioning and Routing
    @ArchTest
    static final ArchRule test5_ControllersMustMapToApiV1 = classes()
            .that().resideInAPackage("..statement.controller..")
            .and().haveSimpleNameEndingWith("Controller")
            .should(new ArchCondition<JavaClass>("map to /api/v1/statements") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    boolean matches = item.tryGetAnnotationOfType(RequestMapping.class)
                            .map(annotation -> {
                                for (String val : annotation.value()) {
                                    if (val.startsWith("/api/v1/statements")) return true;
                                }
                                for (String val : annotation.path()) {
                                    if (val.startsWith("/api/v1/statements")) return true;
                                }
                                return false;
                            }).orElse(false);

                    if (!matches) {
                        events.add(SimpleConditionEvent.violated(item, item.getName() + " violates standard API routing at " + item.getName()));
                    }
                }
            })
            .because("We must enforce strict API versioning (/api/v1/*) for all external facing endpoints.");

    // Test 6: Prevent Entity Leakage via HTTP Responses
    @ArchTest
    static final ArchRule test6_ControllerMethodsMustNotReturnEntities = noMethods()
            .that().areDeclaredInClassesThat().resideInAPackage("..statement.controller..")
            .and().arePublic()
            .should().haveRawReturnType(new DescribedPredicate<JavaClass>("an @Entity class") {
                @Override
                public boolean test(JavaClass input) {
                    return input.isAnnotatedWith(Entity.class);
                }
            })
            .because("Returning database entities directly from controllers risks massive PII leakage. Return void for streams, or DTOs.");

    // ==================================================================================
    // 3. DTO GUARDRAILS (THE CONTRACTS)
    // ==================================================================================


    // Test 7: DTO Naming Convention (Updated to ignore inner Builder classes)
    @ArchTest
    static final ArchRule test7_DtoNamingConvention = classes()
            .that().resideInAPackage("..statement.dto..")
            .and().areNotMemberClasses()
            .should().haveSimpleNameEndingWith("Request")
            .orShould().haveSimpleNameEndingWith("Response")
            .because("Network payloads must clearly identify their directionality.");

    // Test 8: Force Immutability on DTOs (Updated to ignore inner Builder classes)
    @ArchTest
    static final ArchRule test8_DtosMustBeImmutableRecords = classes()
            .that().resideInAPackage("..statement.dto..")
            .and().areNotMemberClasses()
            .should().beRecords()
            .because("DTOs must be 100% immutable to prevent concurrent thread modification during request parsing.");

    // Test 9: Force Validation on Incoming Requests
    @ArchTest
    static final ArchRule test9_RequestDtosMustHaveValidation = fields()
            .that().areDeclaredInClassesThat().resideInAPackage("..statement.dto..")
            .and().areDeclaredInClassesThat().haveSimpleNameEndingWith("Request")
            .should(new ArchCondition<JavaField>("have jakarta.validation.constraints") {
                @Override
                public void check(JavaField item, ConditionEvents events) {
                    boolean hasConstraint = item.getAnnotations().stream()
                            .anyMatch(a -> a.getRawType().getPackageName().startsWith("jakarta.validation.constraints"));
                    if (!hasConstraint) {
                        events.add(SimpleConditionEvent.violated(item, item.getFullName() + " lacks a validation constraint."));
                    }
                }
            })
            .because("Every field in a Request DTO must be defended at the boundary against bad actors.");

    // ==================================================================================
    // 4. SERVICE GUARDRAILS (THE BRAINS)
    // ==================================================================================

    // Test 10: Service Annotation Enforcement
    @ArchTest
    static final ArchRule test10_ServicesMustBeAnnotatedWithService = classes()
            .that().resideInAPackage("..statement.service..")
            .and().haveSimpleNameEndingWith("Service")
            .should().beAnnotatedWith(Service.class);

    // Test 11: Transactional Boundary Enforcement
    @ArchTest
    static final ArchRule test11_ServiceMethodsMustBeTransactional = methods()
            .that().areDeclaredInClassesThat().resideInAPackage("..statement.service..")
            .and().areDeclaredInClassesThat().haveSimpleNameEndingWith("Service")
            .and().arePublic()
            .should().beAnnotatedWith(Transactional.class)
            .because("Public service methods must explicitly declare their database transaction boundaries.");

    // Test 12: Service Access Restriction
    @ArchTest
    static final ArchRule test12_ServicesMustNotBeExposedDirectly = classes()
            .that().resideInAPackage("..statement.service..")
            .should().onlyBeAccessed().byAnyPackage("..statement.controller..", "..statement.service..", "..statement.dto..")
            .because("Statement services are closed components and must not be invoked randomly by external utilities.");

    // ==================================================================================
    // 5. SECURITY & BEST PRACTICES (THE OPAQUE STANDARDS)
    // ==================================================================================

    // Test 13: Strictly Ban System.out / System.err in the Statement Domain
    @ArchTest
    static final ArchRule test13_NoSystemOutOrErr = noClasses()
            .that().resideInAPackage("..statement..")
            .should(GeneralCodingRules.ACCESS_STANDARD_STREAMS)
            .because("All output must be routed through Slf4j to ensure PII is masked and logs are structured for CloudWatch.");

    // Test 14: Prevent Generic Exceptions (Safely Scoped to Statement Domain ONLY)
    @ArchTest
    static final ArchRule test14_NoGenericExceptionsThrown = noClasses()
            .that().resideInAPackage("..statement..")
            .should(GeneralCodingRules.THROW_GENERIC_EXCEPTIONS)
            .because("Throwing generic 'Exception' or 'RuntimeException' prevents the GlobalExceptionHandler from mapping accurate HTTP status codes.");

}