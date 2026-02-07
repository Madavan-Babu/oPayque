package com.opayque.api.transactions.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opayque.api.infrastructure.exception.IdempotencyException;
import com.opayque.api.transactions.dto.TransferRequest;
import com.opayque.api.transactions.service.TransferService;
import com.opayque.api.wallet.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * Integration test suite for the Transfer Controller acting as the "Fortress Gate" of the Atomic Transfer Engine.
 * 
 * <p>This test validates the complete request-response cycle through the controller layer, ensuring:
 * <ul>
 *   <li>JWT-based authentication is properly enforced (401 Unauthorized for missing/invalid tokens)</li>
 *   <li>BOLA (Broken Object Level Authorization) prevention via strict security context enforcement</li>
 *   <li>Rate limiting protection against DDoS and brute force attacks (10 requests/minute per user)</li>
 *   <li>Idempotency guarantees preventing duplicate transactions and double-spending</li>
 *   <li>Input validation for financial precision (rejects negative/zero amounts)</li>
 * </ul>
 * 
 * <p><b>Test Infrastructure:</b>
 * <ul>
 *   <li>Redis TestContainer for distributed rate limiting and idempotency storage</li>
 *   <li>MockMvc for HTTP request simulation without starting full web server</li>
 *   <li>MockitoBean for isolating controller tests from downstream service logic</li>
 * </ul>
 * 
 * <p><b>Security Posture:</b> Implements defense-in-depth with multiple security layers including
 * authentication, authorization, rate limiting, and idempotency controls as mandated by PCI DSS
 * and OWASP Top 10 compliance requirements.
 * 
 * @author Madavan Babu
 * @version 2.0.0
 * @since 2026
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers
class TransferControllerIntegrationTest {

    /**
     * Redis container for distributed caching of rate limit counters and idempotency keys.
     * Uses Redis 7-alpine for minimal resource footprint while maintaining enterprise-grade
     * performance for financial transaction processing.
     */
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    /**
     * Configures Spring Boot to use the TestContainer Redis instance instead of default configuration.
     * Ensures test isolation by providing dedicated Redis instance per test execution.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    // We mock the Business Logic because we tested it thoroughly in Story 3.1 & 3.2.
    // This test focuses purely on the "Gateway" (Controller + RateLimit + Security).
    @MockitoBean
    private TransferService transferService;

    @MockitoBean
    private AccountService accountService;

    /**
     * Pre-test setup ensuring complete isolation between test executions.
     * 
     * <p>Flushes all Redis data to prevent:
     * <ul>
     *   <li>Rate limit counters persisting across tests</li>
     *   <li>Idempotency keys interfering with subsequent test runs</li>
     *   <li>Test contamination from previous executions</li>
     * </ul>
     * 
     * <p>Critical for maintaining test reliability in CI/CD pipelines where test order
     * execution is non-deterministic.
     */
    @BeforeEach
    void setup() {
        // Clear Redis before every test to ensure the Rate Limiter starts fresh
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    /**
     * Validates successful fund transfer through all security layers.
     * 
     * <p>Test Flow:
     * <ol>
     *   <li>JWT authentication simulation via {@link WithMockUser}</li>
     *   <li>Account resolution preventing BOLA attacks</li>
     *   <li>Business logic delegation to mocked service layer</li>
     *   <li>Successful transaction completion response</li>
     * </ol>
     * 
     * <p>Security Validation: Ensures the controller correctly extracts authenticated
     * user identity from SecurityContext rather than trusting client-provided data.
     */
    @Test
    @DisplayName("Happy Path: Valid Authenticated Request -> 200 OK")
    @WithMockUser(username = "sender@opayque.com") // Simulates a valid JWT
    void shouldProcessValidTransfer() throws Exception {
        // Arrange
        TransferRequest request = new TransferRequest("receiver@opayque.com", "100.00", "USD");
        UUID mockSenderId = UUID.randomUUID();
        UUID mockTransferId = UUID.randomUUID();

        // Mock the User ID resolution (The BOLA Check)
        when(accountService.getAccountIdByEmail("sender@opayque.com", "USD")).thenReturn(mockSenderId);

        // Mock the Service Execution
        when(transferService.transferFunds(eq(mockSenderId), any(), any(), any(), any()))
                .thenReturn(mockTransferId);

        // Act & Assert
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idemp-happy-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value(mockTransferId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

  /**
   * Validates authentication gatekeeping for the Atomic Transfer Engine endpoint.
   *
   * <p>Ensures that every inbound funds-transfer request is mandatorily accompanied by a
   * cryptographically signed JWT bearer token, aligning with:
   *
   * <ul>
   *   <li><b>PCI DSS Req. 8.2.1:</b> Strong authentication for all external access
   *   <li><b>OWASP API #1:</b> Broken Object Level Authorization prevention
   *   <li><b>FedLine Security:</b> Zero-trust posture for high-value payment rails
   * </ul>
   *
   * <p>Threat Mitigation:
   *
   * <ul>
   *   <li>Blocks anonymous invocation that could facilitate money-laundering mule accounts
   *   <li>Prevents reconnaissance probes against transaction metadata
   *   <li>Eliminates unauthenticated submission of idempotency keys that could poison the store
   * </ul>
   *
   * <p>Compliance Trace: A 401 response here is logged and forwarded to the SIEM, feeding real-time
   * risk scoring for adaptive authentication workflows.
   */
  @Test
  @DisplayName("The Ghost: No Auth Header -> 401 Unauthorized")
  void shouldRejectUnauthenticatedRequest() throws Exception {
        TransferRequest request = new TransferRequest("receiver@opayque.com", "100.00", "USD");

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idemp-ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized()); // Spring Security Default
    }

    /**
     * Critical security test preventing Broken Object Level Authorization (BOLA) attacks.
     * 
     * <p>Verifies that the controller exclusively uses the authenticated user's identity
     * from the SecurityContext, preventing attackers from:
     * <ul>
     *   <li>Spoofing other users' identities</li>
     *   <li>Accessing unauthorized accounts</li>
     *   <li>Executing transfers on behalf of other customers</li>
     * </ul>
     * 
     * <p>Financial Impact: Prevents unauthorized fund transfers that could result in
     * regulatory violations and financial losses.
     */
    @Test
    @DisplayName("The Imposter (BOLA): Service MUST use Security Context Email, not Body")
    @WithMockUser(username = "attacker@opayque.com")
    void shouldStrictlyUseAuthenticatedIdentity() throws Exception {
        // Arrange
        TransferRequest request = new TransferRequest("victim@opayque.com", "100.00", "USD");
        UUID attackerId = UUID.randomUUID();

        // We explicitly verify that the Controller calls the service with "attacker@opayque.com"
        // even if they tried to spoof something else in a theoretical (non-existent) body field.
        when(accountService.getAccountIdByEmail("attacker@opayque.com", "USD")).thenReturn(attackerId);

        // Act
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idemp-bola")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        // Assert: The Critical Check
        verify(accountService).getAccountIdByEmail("attacker@opayque.com", "USD");
        verify(transferService).transferFunds(eq(attackerId), any(), any(), any(), any());
    }

    /**
     * Validates distributed rate limiting protection against DDoS and brute force attacks.
     * 
     * <p>Configuration: 10 requests per minute per authenticated user as per
     * OWASP API Security recommendations for financial services.
     * 
     * <p>Business Impact: Prevents:
     * <ul>
     *   <li>Automated transfer spam attacks</li>
     *   <li>Resource exhaustion on payment processing systems</li>
     *   <li>Regulatory violations from unchecked high-frequency trading</li>
     * </ul>
     * 
     * <p>Technical Implementation: Uses Redis-backed token bucket algorithm for
     * horizontal scalability across multiple API gateway instances.
     */
    @Test
    @DisplayName("The Spammer: 11th Request must fail (429 Too Many Requests)")
    @WithMockUser(username = "spammer@opayque.com")
    void shouldEnforceRateLimit() throws Exception {
        // Arrange
        TransferRequest request = new TransferRequest("receiver@opayque.com", "1.00", "USD");
        UUID mockId = UUID.randomUUID();
        when(accountService.getAccountIdByEmail(any(), any())).thenReturn(mockId);

        // Act: Fire 10 allowed requests
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/transfers")
                            .header("Idempotency-Key", "idemp-" + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        // Assert: The 11th Request MUST FAIL
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idemp-blocked")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests()) // 429
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
    }

    /**
     * Ensures idempotency protection preventing duplicate transaction processing.
     * 
     * <p>Critical for maintaining ACID properties in distributed financial systems where
     * network timeouts and retries are common. Prevents:
     * <ul>
     *   <li>Double-spending attacks</li>
     *   <li>Duplicate fund transfers from client retries</li>
     *   <li>Database inconsistencies from repeated requests</li>
     * </ul>
     * 
     * <p>Implementation: Redis-stored idempotency keys with TTL to balance between
     * duplicate prevention and storage efficiency.
     */
    @Test
    @DisplayName("The Replay: Duplicate Idempotency Key -> 409 Conflict")
    @WithMockUser(username = "sender@opayque.com")
    void shouldHandleIdempotencyConflict() throws Exception {
        TransferRequest request = new TransferRequest("receiver@opayque.com", "100.00", "USD");
        UUID mockId = UUID.randomUUID();
        when(accountService.getAccountIdByEmail(any(), any())).thenReturn(mockId);

        // Mock the Service throwing the Conflict (simulating logic from Story 3.2)
        when(transferService.transferFunds(any(), any(), any(), any(), eq("idemp-duplicate")))
                .thenThrow(new IdempotencyException("Transaction already processed"));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idemp-duplicate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict()) // 409
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    /**
     * Validates input sanitization preventing negative monetary amounts.
     * 
     * <p>Financial Compliance: Ensures all transactions comply with positive value
     * requirements as per banking regulations and prevents potential fraud vectors
     * through credit manipulation attempts.
     * 
     * <p>Security Posture: Input validation represents the first line of defense
     * in the OWASP Top 10 injection prevention strategy.
     */
    @Test
    @DisplayName("Validation: Negative Amount -> 400 Bad Request")
    @WithMockUser(username = "sender@opayque.com")
    void shouldRejectInvalidInput() throws Exception {
        // Invalid Request (Negative Money)
        TransferRequest request = new TransferRequest("receiver@opayque.com", "-50.00", "USD");

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idemp-validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest()) // 400
                .andExpect(jsonPath("$.amount").exists()); // Error detail field
    }

    /**
     * Enforces business rule requiring non-zero transfer amounts.
     * 
     * <p>Prevents wasteful blockchain-style dust transactions that could:
     * <ul>
     *   <li>Clog the payment processing pipeline</li>
     *   <li>Consume unnecessary computational resources</li>
     *   <li>Violate minimum transfer amount policies</li>
     * </ul>
     * 
     * <p>Regulatory Compliance: Aligns with AML (Anti-Money Laundering) requirements
     * that typically mandate minimum transaction thresholds.
     */
    @Test
    @DisplayName("Validation: Zero Amount -> 400 Bad Request (Business Rule)")
    @WithMockUser(username = "sender@opayque.com")
    void shouldRejectZeroAmount() throws Exception {
        // Arrange
        TransferRequest request = new TransferRequest("receiver@opayque.com", "0.00", "USD");
        UUID mockId = UUID.randomUUID();
        when(accountService.getAccountIdByEmail(any(), any())).thenReturn(mockId);

        // Mock the Service throwing the exception (since we are mocking the service here)
        // IMPORTANT: In a real E2E test, the service logic would run.
        // Here, we simulate the Service enforcing the rule.
        doThrow(new IllegalArgumentException("Transfer amount must be greater than zero"))
                .when(transferService).transferFunds(any(), any(), eq("0.00"), any(), any());

        // Act & Assert
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "idemp-zero")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest()) // 400
                .andExpect(jsonPath("$.message").value("Transfer amount must be greater than zero"));
    }
}