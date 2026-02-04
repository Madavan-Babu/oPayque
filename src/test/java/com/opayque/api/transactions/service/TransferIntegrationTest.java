package com.opayque.api.transactions.service;

import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.infrastructure.exception.InsufficientFundsException;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.LedgerEntry;
import com.opayque.api.wallet.entity.TransactionType;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.repository.LedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TransferIntegrationTest {

    // --- INFRASTRUCTURE (Postgres + Redis) ---
    @Container
    static final GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse("postgres:15-alpine"))
            .withEnv("POSTGRES_DB", "opayque_test")
            .withEnv("POSTGRES_USER", "ci_user")
            .withEnv("POSTGRES_PASSWORD", "ci_password")
            .withExposedPorts(5432);

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/opayque_test");
        registry.add("spring.datasource.username", () -> "ci_user");
        registry.add("spring.datasource.password", () -> "ci_password");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "60");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "10");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "30000");
    }

    // --- DEPENDENCIES ---
    @Autowired private TransferService transferService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerRepository ledgerRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanSlate() {
        ledgerRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Should persist atomic transfer with linked Reference ID in Real DB")
    void shouldPersistTransferAtomicallyWithReferenceId() {
        // 1. Arrange
        User sender = createUser("sender@opayque.com", "Sender User");
        Account senderAccount = createAccount(sender, "USD");
        seedFunds(senderAccount, new BigDecimal("500.00"));

        User receiver = createUser("receiver@opayque.com", "Receiver User");
        Account receiverAccount = createAccount(receiver, "USD");

        // 2. Act
        BigDecimal transferAmount = new BigDecimal("100.00");
        // FIX: Passing senderAccount.getId() (Account ID), NOT sender.getId() (User ID)
        transferService.transferFunds(senderAccount.getId(), receiver.getEmail(), transferAmount.toString(), "USD");

        // 3. Assert
        List<LedgerEntry> senderEntries = ledgerRepository.findByAccount(senderAccount);
        List<LedgerEntry> receiverEntries = ledgerRepository.findByAccount(receiverAccount);

        LedgerEntry debitEntry = senderEntries.stream()
                .filter(e -> e.getTransactionType() == TransactionType.DEBIT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Debit entry not found"));

        LedgerEntry creditEntry = receiverEntries.stream()
                .filter(e -> e.getTransactionType() == TransactionType.CREDIT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Credit entry not found"));

        assertThat(debitEntry.getReferenceId()).isNotNull();
        assertThat(debitEntry.getReferenceId()).isEqualTo(creditEntry.getReferenceId());
        assertThat(debitEntry.getAmount()).isEqualByComparingTo(transferAmount);

        assertThat(debitEntry.getDirection()).isEqualTo("OUT");
        assertThat(creditEntry.getDirection()).isEqualTo("IN");
    }

    @Test
    @DisplayName("Should update real balances correctly after transfer")
    void shouldUpdateBalancesCorrectly() {
        // 1. Arrange
        User sender = createUser("sender_math@opayque.com", "Math Sender");
        Account senderAccount = createAccount(sender, "USD");
        seedFunds(senderAccount, new BigDecimal("100.00")); // Start: $100

        User receiver = createUser("receiver_math@opayque.com", "Math Receiver");
        Account receiverAccount = createAccount(receiver, "USD"); // Start: $0

        // 2. Act
        // FIX: Passing Account ID
        transferService.transferFunds(senderAccount.getId(), receiver.getEmail(), "40.00", "USD");

        // 3. Assert
        BigDecimal senderBalance = ledgerRepository.getBalance(senderAccount.getId());
        BigDecimal receiverBalance = ledgerRepository.getBalance(receiverAccount.getId());

        assertThat(senderBalance).isEqualByComparingTo("60.00");
        assertThat(receiverBalance).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("Should remain atomic (Rollback) on Insufficient Funds")
    void shouldRollbackOnInsufficientFunds() {
        // 1. Arrange
        User sender = createUser("broke@opayque.com", "Broke Sender");
        Account senderAccount = createAccount(sender, "USD");
        seedFunds(senderAccount, new BigDecimal("10.00")); // Only $10

        User receiver = createUser("rich@opayque.com", "Rich Receiver");
        createAccount(receiver, "USD");

        // 2. Act & Assert
        // FIX: Passing Account ID AND checking for specific Exception
        assertThatThrownBy(() ->
                transferService.transferFunds(senderAccount.getId(), receiver.getEmail(), "50.00", "USD")
        )
                .isInstanceOf(InsufficientFundsException.class); // This confirms it failed for the RIGHT reason

        // 3. Assert
        List<LedgerEntry> entries = ledgerRepository.findByAccount(senderAccount);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getDescription()).isEqualTo("Seed Funds");
    }

    // --- HELPERS ---

    private User createUser(String email, String fullName) {
        // FIX: saveAndFlush to ensure visibility in transactions
        return userRepository.saveAndFlush(User.builder()
                .email(email)
                .fullName(fullName)
                .password(passwordEncoder.encode("password123"))
                .role(Role.CUSTOMER)
                .build());
    }

    private Account createAccount(User user, String currency) {
        // FIX: saveAndFlush
        return accountRepository.saveAndFlush(Account.builder()
                .user(user)
                .currencyCode(currency)
                .iban("XX" + System.nanoTime())
                .build());
    }

    private void seedFunds(Account account, BigDecimal amount) {
        // FIX: saveAndFlush
        ledgerRepository.saveAndFlush(LedgerEntry.builder()
                .account(account)
                .amount(amount)
                .currency(account.getCurrencyCode())
                .transactionType(TransactionType.CREDIT)
                .direction("IN")
                .originalAmount(amount)
                .originalCurrency(account.getCurrencyCode())
                .exchangeRate(BigDecimal.ONE)
                .recordedAt(LocalDateTime.now())
                .description("Seed Funds")
                .referenceId(null)
                .build());
    }
}