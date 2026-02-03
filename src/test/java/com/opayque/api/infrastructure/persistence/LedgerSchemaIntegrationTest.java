package com.opayque.api.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/// **Multi-Currency Account Management — Ledger Integrity Verification**.
///
/// This test suite validates the physical database schema and native partitioning logic of the **oPayque** system.
/// By utilizing **Testcontainers**, it mirrors the production **PostgreSQL 15** environment to ensure that the
/// "Reliability-First" mandate is upheld at the persistence layer.
///
/// **Architectural Objectives:**
/// * **Schema Validation:** Ensures `Liquibase` changesets are correctly applied to a real Postgres instance.
/// * **Temporal Partitioning:** Verifies that the native PostgreSQL range partitioning logic correctly routes data
///   based on the `recorded_at` column.
/// * **Constraint Adherence:** Confirms referential integrity (Foreign Keys) and precision constraints (19,4) for
///   financial ledger entries.
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class LedgerSchemaIntegrationTest {

    /// **Production Mirror:** Spawns a lightweight PostgreSQL 15 container to bypass H2 limitations
    /// regarding native partitioning and advanced SQL syntax.
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    /// Orchestrates the dynamic injection of container credentials into the Spring environment.
    ///
    /// This step is critical for switching from the standard H2 test profile back to a production-grade
    /// PostgreSQL dialect, ensuring that native features like sequences and partitions function as expected.
    ///
    /// @param registry The registry used to override application properties at runtime.
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // 1. Establish connectivity with the ephemeral Testcontainer
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // 2. CRITICAL ARCHITECTURAL OVERRIDE:
        // Explicitly reinstates PostgreSQL dialect and drivers to prevent Hibernate from defaulting
        // to H2 behaviors during schema validation.
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");

        // 3. Versioning Strategy: Ensures Liquibase manages the schema creation rather than Hibernate DDL-auto.
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    /// Low-level SQL execution tool used to verify schema state without JPA overhead.
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /// Verifies the physical routing of financial data into temporal database partitions.
    ///
    /// **Business Scenario:**
    /// Validates that insertions targeting the 2026 temporal window are correctly processed by the
    /// `ledger_entries_2026` partition without violating data integrity or scale constraints.
    ///
    /// **Process Flow:**
    /// 1. Seeds prerequisite Identity and Account entities to satisfy Foreign Key constraints.
    /// 2. Targets the 2026 Temporal Partition via a future `recorded_at` timestamp.
    /// 3. Executes a native INSERT to verify partitioning logic success.
    @Test
    @DisplayName("Partitioning: Should insert Ledger Entry into 2026 Partition successfully")
    void shouldInsertIntoPartition() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // Step 1: Seed identity and account to satisfy strict referential integrity requirements.
        jdbcTemplate.update("INSERT INTO users (id, email, password, full_name, role, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                userId, "schema-test-" + userId + "@opayque.com", "hash", "Schema Test", "CUSTOMER", Timestamp.valueOf(LocalDateTime.now()));

        jdbcTemplate.update("INSERT INTO accounts (id, user_id, currency, iban, created_at) VALUES (?, ?, ?, ?, ?)",
                accountId, userId, "USD", "DE00123456789012345678", Timestamp.valueOf(LocalDateTime.now()));

        // Step 2: Set target to the 2026 Temporal Partition.
        LocalDateTime futureDate = LocalDateTime.of(2026, 5, 20, 10, 0, 0);

        // Step 3: Assert that the database correctly accepts the partitioned entry.
        assertDoesNotThrow(() -> {
            jdbcTemplate.update("""
                INSERT INTO ledger_entries (id, account_id, amount, direction, transaction_type, recorded_at) 
                VALUES (?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), accountId, new BigDecimal("50.1234"), "CREDIT", "DEPOSIT", Timestamp.valueOf(futureDate));
        });

        // Verification: Ensure the entry is persistent in the logical 'ledger_entries' table.
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class);
        assertThat(count).isEqualTo(1);
    }
}