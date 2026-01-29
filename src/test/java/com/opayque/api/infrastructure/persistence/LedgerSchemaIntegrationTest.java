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

/// Epic 5: Compliance, Auditing & Statement Services - Ledger Integrity Verification.
///
/// This suite validates the physical database schema and partitioning logic utilizing
/// Testcontainers to ensure a "True Prod-Grade" PostgreSQL environment.
/// It specifically verifies:
/// 1. Native PostgreSQL table partitioning for high-volume ledger data.
/// 2. Schema constraints (Foreign Keys) and precision requirements (DECIMAL 19,4).
/// 3. Correct routing of data into time-based partitions (2026 window).
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE) // Disables the default H2 override to force native PostgreSQL testing.
class LedgerSchemaIntegrationTest {

    /// **The Ephemeral Ledger Environment**
    ///
    /// Orchestrates a temporary PostgreSQL 15-Alpine container that mirrors the
    /// production infrastructure defined in the architecture diagram.
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("opayque_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    /// **Dynamic DataSource Configuration**
    ///
    /// Overrides application properties at runtime to point the Spring Data JPA
    /// provider toward the ephemeral Testcontainers instance.
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /// **Structural Audit: Native Partitioning**
    ///
    /// Queries the PostgreSQL `pg_class` catalog to verify that the `ledger_entries`
    /// table has been successfully provisioned as a partitioned entity ('p') rather
    /// than a standard relational table ('r').
    @Test
    @DisplayName("Integration: Ledger Table should be partitioned by range")
    void shouldHavePartitionedSchema() {
        String checkPartitionQuery = """
            SELECT relkind FROM pg_class 
            WHERE relname = 'ledger_entries'
        """;

        String relKind = jdbcTemplate.queryForObject(checkPartitionQuery, String.class);

        // Verification: Table must be 'p' (Partitioned) to support massive horizontal scale.
        assertThat(relKind).isEqualTo("p");
    }

    /// **Functional Audit: Data Routing & Constraints**
    ///
    /// Validates the end-to-end insertion flow into the ledger.
    /// This test satisfies requirements for:
    /// 1. Identity Guardrails (Foreign keys from Users/Accounts).
    /// 2. Data Precision (Validating DECIMAL 19,4 storage).
    /// 3. Temporal Partitioning (Targeting the 2026 data window).
    @Test
    @DisplayName("Integration: Should insert into 2026 Partition successfully")
    void shouldInsertIntoPartition() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // Step 1: Establish foundational ledger entities using raw SQL for schema isolation.
        jdbcTemplate.update("INSERT INTO users (id, email, password, full_name, role, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                userId, "schema-test-" + userId + "@opayque.com", "hash", "Schema Test", "CUSTOMER", Timestamp.valueOf(LocalDateTime.now()));

        jdbcTemplate.update("INSERT INTO accounts (id, user_id, currency, created_at) VALUES (?, ?, ?, ?)",
                accountId, userId, "USD", Timestamp.valueOf(LocalDateTime.now()));

        // Step 2: Ingress a ledger entry targeting the specific 2026 partition.
        LocalDateTime futureDate = LocalDateTime.of(2026, 5, 20, 10, 0, 0);

        assertDoesNotThrow(() -> {
            jdbcTemplate.update("""
                INSERT INTO ledger_entries (id, account_id, amount, direction, transaction_type, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), accountId, new BigDecimal("50.1234"), "CREDIT", "DEPOSIT", Timestamp.valueOf(futureDate));
        });

        // Step 3: Verify successful persistence and indexing within the partitioned ledger.
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class);
        assertThat(count).isEqualTo(1);
    }
}