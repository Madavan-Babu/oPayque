package com.opayque.api.wallet.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.identity.service.JwtService;
import com.opayque.api.wallet.dto.CreateAccountRequest;
import com.opayque.api.wallet.repository.AccountRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/// Multi-Currency Account Management - Wallet Lifecycle Integration Audit.
///
/// This suite executes end-to-end functional testing of the wallet provisioning pipeline.
/// It validates the interplay between security filters, identity contexts,
/// jurisdictional IBAN generation, and the global exception handling mechanism.
///
/// Testing Strategy: Utilizes MockMvc for high-fidelity HTTP simulation and
/// H2/Testcontainers for persistence verification.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WalletLifecycleIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    private String validToken;
    private User testUser;

    /// Orchestrates the security context and identity state for each test execution.
    ///
    /// Purges existing ledger data and establishes a fresh [User] identity.
    /// Explicitly flushes the persistence context to ensure the security filter
    /// chain can resolve the identity during the request lifecycle.
    @BeforeEach
    void setup() {
        accountRepository.deleteAll();
        userRepository.deleteAll();

        // Establish a verifiable user identity for the OPayque ecosystem.
        testUser = User.builder()
                .email("saver@opayque.com")
                .password(passwordEncoder.encode("SecurePass123!"))
                .role(Role.CUSTOMER)
                .fullName("Saver One")
                .build();

        userRepository.save(testUser);
        userRepository.flush(); // Forces synchronization with the persistence layer.

        // Generates an authorized Bearer token for simulated authenticated requests.
        validToken = "Bearer " + jwtService.generateToken(testUser.getEmail(), "ROLE_" + testUser.getRole().name());
    }

    /// Scenario: Successful Wallet Provisioning.
    ///
    /// Verifies that a valid currency request (e.g., GBP) results in a 201 Created
    /// status, an opaque ID, and a masked IBAN in the API response.
    ///
    /// Audit Trail: Confirms the real IBAN is correctly persisted in the database ledger.
    @Test
    @DisplayName("Integration: Should create a new Wallet with valid IBAN and Currency")
    void shouldCreateAccountSuccessfully() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest("GBP");

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.currencyCode").value("GBP"))
                // Security Check: Ensure the IBAN is masked during egress serialization.
                .andExpect(jsonPath("$.iban").value(Matchers.containsString("***")));

        // Logic Check: Verify state persistence within the account repository.
        boolean exists = accountRepository.existsByUserIdAndCurrencyCode(testUser.getId(), "GBP");
        assertThat(exists).isTrue();
    }

    /// Scenario: 1:1 Currency Constaint Enforcement.
    ///
    /// Ensures that duplicate wallet creation attempts for the same currency
    /// are rejected with a 409 Conflict, satisfying the business rule of
    /// one wallet per currency per identity.
    @Test
    @DisplayName("Integration: Should reject duplicate wallet creation (1:1 Rule)")
    void shouldRejectDuplicateWallet() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest("EUR");

        // Step 1: Establish the primary wallet instance.
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Step 2: Attempt unauthorized duplicate provisioning.
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict()); // Mapped from IllegalStateException.
    }

    /// Scenario: Non-ISO Currency Rejection.
    ///
    /// Validates that malformed or unsupported currency codes (e.g., "DOGE")
    /// are rejected via Bean Validation before hitting the service layer.
    @Test
    @DisplayName("Integration: Should reject Invalid ISO Currency Codes")
    void shouldRejectInvalidCurrency() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest("DOGE");

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    /// Scenario: Strict ISO Case Sensitivity.
    ///
    /// Confirms that lowercase currency codes are rejected to maintain strict
    /// ISO 4217 compliance and prevent data inconsistency in the ledger.
    @Test
    @DisplayName("Validation: Should reject lowercase currency codes (Strict ISO 4217)")
    void shouldRejectLowercaseCurrency() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest("eur");

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}