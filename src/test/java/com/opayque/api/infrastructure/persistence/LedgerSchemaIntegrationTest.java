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

/// Multi-Currency Account Management - Ledger Integrity Verification.
///
/// Validates the physical database schema and native partitioning logic.
/// Utilizes Testcontainers to mirror the production PostgreSQL 15 environment.
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class LedgerSchemaIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // 1. Connect to Container
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // 2. CRITICAL FIX: Override the H2 settings from application-test.yaml
        // We must tell Hibernate we are back on Postgres for this specific test.
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // 3. Liquibase Config
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /// Verifies routing of financial data into temporal partitions.
    ///
    /// Validates that insertions targeting the 2026 window are correctly stored
    /// without violating schema constraints (FKs/Precision).
    @Test
    @DisplayName("Partitioning: Should insert Ledger Entry into 2026 Partition successfully")
    void shouldInsertIntoPartition() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // Seeding identity and account to satisfy referential integrity.
        jdbcTemplate.update("INSERT INTO users (id, email, password, full_name, role, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                userId, "schema-test-" + userId + "@opayque.com", "hash", "Schema Test", "CUSTOMER", Timestamp.valueOf(LocalDateTime.now()));

        jdbcTemplate.update("INSERT INTO accounts (id, user_id, currency, iban, created_at) VALUES (?, ?, ?, ?, ?)",
                accountId, userId, "USD", "DE00123456789012345678", Timestamp.valueOf(LocalDateTime.now()));

        // Target: 2026 Temporal Partition.
        LocalDateTime futureDate = LocalDateTime.of(2026, 5, 20, 10, 0, 0);

        assertDoesNotThrow(() -> {
            jdbcTemplate.update("""
                INSERT INTO ledger_entries (id, account_id, amount, direction, transaction_type, recorded_at) 
                VALUES (?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), accountId, new BigDecimal("50.1234"), "CREDIT", "DEPOSIT", Timestamp.valueOf(futureDate));
        });

        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class);
        assertThat(count).isEqualTo(1);
    }
}