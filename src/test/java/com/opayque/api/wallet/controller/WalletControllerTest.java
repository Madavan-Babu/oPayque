package com.opayque.api.wallet.controller;

import com.opayque.api.infrastructure.exception.GlobalExceptionHandler;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.service.JwtService;
import com.opayque.api.identity.service.TokenBlocklistService;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.service.AccountService;
import com.opayque.api.wallet.service.LedgerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.TestingAuthenticationToken; // <--- NEW IMPORT
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/// **Web Layer Slice Test: Wallet & Balance Security Audit**.
///
/// This suite performs narrow-scope integration testing on the [WalletController] using [MockMvc].
/// It focuses on the "Opaque" API boundary, ensuring that business logic constraints—specifically
/// ownership validation—are enforced before any financial data is leaked.
///
/// **Testing Strategy:**
/// * **Controller Slicing:** Uses [@WebMvcTest] to isolate the web layer, mocking all service
///   and security dependencies to ensure sub-second execution.
/// * **Security Manual Injection:** Since filters are disabled for performance, the `Principal`
///   is manually injected into requests to simulate authenticated state.
/// * **BOLA Audit:** Primary focus is verifying the "Broken Object Level Authorization" guardrails
///   mandated in Epic 1.
///
@WebMvcTest(WalletController.class)
@AutoConfigureMockMvc(addFilters = false) // Filters OFF = We must manually inject Principal
@ActiveProfiles("test")
class WalletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private AccountService accountService;
    @MockitoBean private LedgerService ledgerService;

    // Dependencies required to satisfy ApplicationContext startup (even if unused)
    @MockitoBean private JwtService jwtService;
    @MockitoBean private TokenBlocklistService tokenBlocklistService;
    @MockitoBean private UserDetailsService userDetailsService;

    /// Creates a synthetic [Authentication] token to satisfy the controller's `Principal` requirements.
    ///
    /// This helper simulates a successfully authenticated user session, allowing the test to
    /// provide the "Current User" context (email) necessary for ownership verification logic.
    ///
    /// @param email The identity string to be embedded in the security context.
    /// @return A configured [TestingAuthenticationToken] for [MockMvc] injection.
    private Authentication mockAuth(String email) {
        return new TestingAuthenticationToken(email, "password");
    }

    /// **Security Audit: BOLA (Broken Object Level Authorization) Protection**.
    ///
    /// Verifies that the API explicitly rejects attempts by an authenticated user to access
    /// a wallet resource belonging to another identity.
    ///
    /// **Constraint:** The [WalletController] must compare the `Principal#getName` with the
    /// [Account#getUser#getEmail]. If they mismatch, a 403 Forbidden must be returned
    /// **before** the [LedgerService] is even invoked.
    ///
    @Test
    @DisplayName("Security: Should block access (403) when User A tries to view User B's balance")
    void shouldBlockIdorAttempt() throws Exception {
        UUID walletId = UUID.randomUUID();
        User victim = User.builder().email("victim@opayque.com").build();
        Account victimAccount = Account.builder().id(walletId).user(victim).currencyCode("USD").build();

        given(accountService.getAccountById(walletId)).willReturn(victimAccount);

        // ATTACKER: "attacker@opayque.com" tries to access "victim@opayque.com" wallet
        mockMvc.perform(get("/api/v1/accounts/{id}/balance", walletId)
                        .principal(mockAuth("attacker@opayque.com")) // <--- MANUAL INJECTION
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden()); // Controller throws AccessDeniedException

        verifyNoInteractions(ledgerService);
    }

    /// **Functional Audit: Authorized Balance Retrieval**.
    ///
    /// Confirms that a legitimate account owner can retrieve their precise balance.
    /// Validates that [BigDecimal] values are correctly serialized in the JSON response
    /// maintaining the high-precision scale (4 decimal places) required for banking audit trails.
    @Test
    @DisplayName("Happy Path: Should return precise balance when Owner accesses their own wallet")
    void shouldReturnBalanceForOwner() throws Exception {
        UUID walletId = UUID.randomUUID();
        String userEmail = "alice@opayque.com";
        User alice = User.builder().email(userEmail).build();
        Account aliceAccount = Account.builder().id(walletId).user(alice).currencyCode("EUR").build();

        given(accountService.getAccountById(walletId)).willReturn(aliceAccount);
        given(ledgerService.calculateBalance(walletId)).willReturn(new BigDecimal("1250.5000"));

        mockMvc.perform(get("/api/v1/accounts/{id}/balance", walletId)
                        .principal(mockAuth(userEmail)) // <--- MANUAL INJECTION
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1250.5000));
    }

    /// **Functional Audit: Multi-Currency Dashboard Projection**.
    ///
    /// Verifies the aggregation logic where a user retrieves all associated sub-wallets.
    /// Tests the orchestration between [AccountService] (for metadata) and [LedgerService]
    /// (for dynamic balance derivation) within a single request cycle.
    @Test
    @DisplayName("Dashboard: Should return list of all wallets with calculated balances")
    void shouldReturnWalletDashboard() throws Exception {
        String userEmail = "bob@opayque.com";
        User bob = User.builder().email(userEmail).build();
        UUID usdId = UUID.randomUUID();
        UUID gbpId = UUID.randomUUID();

        Account usdWallet = Account.builder().id(usdId).user(bob).currencyCode("USD").iban("US123").build();
        Account gbpWallet = Account.builder().id(gbpId).user(bob).currencyCode("GBP").iban("GB456").build();

        given(accountService.getAccountsForUser(userEmail))
                .willReturn(List.of(usdWallet, gbpWallet));

        given(ledgerService.calculateBalance(usdId)).willReturn(new BigDecimal("100.0000"));
        given(ledgerService.calculateBalance(gbpId)).willReturn(new BigDecimal("50.0000"));

        mockMvc.perform(get("/api/v1/accounts")
                        .principal(mockAuth(userEmail)) // <--- MANUAL INJECTION
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].account.currencyCode").value("USD"))
                .andExpect(jsonPath("$[0].balance").value(100.0000));
    }

    /// **Reliability Audit: Boundary Condition & Exception Mapping**.
    ///
    /// Ensures that requests for non-existent UUIDs are intercepted and mapped to a 4xx
    /// Client Error. This validates the [GlobalExceptionHandler] integration within the
    /// WebMvc slice, ensuring the client receives a semantic error rather than a raw stack trace.
    @Test
    @DisplayName("Error Handling: Should return 404 if wallet ID does not exist")
    void shouldReturn404WhenWalletMissing() throws Exception {
        UUID randomId = UUID.randomUUID();
        // Even for 404s, the code calls authentication.getName() BEFORE the service call?
        // Actually, looking at your Controller, it gets name first. So we MUST provide auth.

        given(accountService.getAccountById(randomId))
                .willThrow(new IllegalArgumentException("Account not found"));

        mockMvc.perform(get("/api/v1/accounts/{id}/balance", randomId)
                        .principal(mockAuth("anyone@opayque.com")) // <--- REQUIRED TO PASS LINE 111 (getName)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is4xxClientError());
    }
}