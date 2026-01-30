package com.opayque.api.wallet.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.identity.service.JwtService;
import com.opayque.api.wallet.repository.AccountRepository;
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

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/// Multi-Currency Account Management - Wallet Security & Authority Audit.
///
/// This suite validates the "Opaque Security" principle, ensuring that the wallet
/// provisioning engine relies strictly on the cryptographically signed JWT for
/// identity authority rather than potentially compromised request payloads.
///
/// It guards against:
/// 1. Identity Spoofing (Injected User IDs).
/// 2. Unauthorized Public Access (Broken Authentication).
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WalletSecurityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    /// Purges the identity and ledger tables before each execution to ensure
    /// absolute isolation between security audit scenarios.
    @BeforeEach
    void setup() {
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    /// Scenario: Identity Authority Enforcement.
    ///
    /// Validates that the system ignores any UserID injected into the request body
    /// (Mass Assignment attack).
    ///
    /// The logic must verify that the wallet is always assigned to the identity
    /// extracted from the `Authorization` token, regardless of malicious payload content.
    @Test
    @DisplayName("Security: Should ignore injected UserID in request body (Token Authority)")
    void shouldIgnoreInjectedUserId() throws Exception {
        // Step 1: Establish victim and attacker identities.
        User victim = userRepository.save(User.builder()
                .email("victim@opayque.com")
                .password(passwordEncoder.encode("pass"))
                .fullName("Victim User")
                .role(Role.CUSTOMER)
                .build());

        User attacker = userRepository.save(User.builder()
                .email("attacker@opayque.com")
                .password(passwordEncoder.encode("pass"))
                .fullName("Attacker User")
                .role(Role.CUSTOMER)
                .build());

        // Step 2: Generate an authorized token for the attacker.
        String attackerToken = "Bearer " + jwtService.generateToken(attacker.getEmail(), "ROLE_" + attacker.getRole().name());

        // Step 3: Construct a malicious payload attempting to open a wallet for the victim.
        Map<String, Object> maliciousPayload = new HashMap<>();
        maliciousPayload.put("currencyCode", "GBP");
        maliciousPayload.put("user_id", victim.getId().toString()); // The injection attempt.

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", attackerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(maliciousPayload)))
                .andExpect(status().isCreated());

        // Assert: Identity Guardrail verified. Victim remains unaffected.
        assertThat(accountRepository.existsByUserIdAndCurrencyCode(victim.getId(), "GBP")).isFalse();
        // Assert: The system correctly attributed the wallet to the token holder (Attacker).
        assertThat(accountRepository.existsByUserIdAndCurrencyCode(attacker.getId(), "GBP")).isTrue();
    }

    /// Scenario: Public Exposure Audit.
    ///
    /// Ensures that unauthenticated requests targeting the wallet infrastructure
    /// are strictly rejected at the filter level with a 401 Unauthorized status.
    @Test
    @DisplayName("Security: Public access should be strictly forbidden")
    void shouldRejectUnauthenticatedAccess() throws Exception {
        Map<String, String> payload = Map.of("currencyCode", "EUR");

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());
    }
}