package com.opayque.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/// High-Level Smoke Test: Application Context Integrity.
///
/// This test verifies that the Spring Boot Application Context can be initialized
/// without exceptions. It serves as the primary gatekeeper for the oPayque
/// "Opaque" architecture, ensuring that all beans, security filters, and
/// database configurations are wired correctly.
///
/// Profile: Utilizes the 'test' profile to leverage H2/Testcontainers for
/// rapid validation in the CI/CD pipeline.
@SpringBootTest
@ActiveProfiles("test")
class ApplicationStartupTest {

    /// Validates successful application bootstapping.
    ///
    /// Executes the main entry point logic of [OPayqueApiApplication] to ensure
    /// the runtime configuration is fundamentally sound.
    @Test
    void applicationShouldStartSuccessfully() {
        assertDoesNotThrow(() -> OPayqueApiApplication.main(new String[] {}));
    }
}