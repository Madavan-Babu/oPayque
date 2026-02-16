package com.opayque.api.card.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opayque.api.card.dto.CardLimitUpdateRequest;
import com.opayque.api.card.entity.CardStatus;
import com.opayque.api.card.entity.VirtualCard;
import com.opayque.api.card.repository.VirtualCardRepository;
import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.RefreshTokenRepository;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.infrastructure.exception.GlobalExceptionHandler;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test verifying the entire stack of "update monthly limit" for a virtual card.
 * <p>
 * This class spins up real infrastructure (Postgres 15 and Redis 7) via Testcontainers,
 * applies the production schema migration, and executes HTTP calls through MockMvc.
 * <br>
 * It validates the flow from API, through Security (AuthN/AuthZ with BOLA protection),
 * Service, Persistence, and side-effects (e.g. Idempotency) that rely on Redis.
 * <p>
 * All tests run under profile {@code "test"} isolated with {@link @Transactional} disabled
 * to ensure behaviour reflects real production persistence.
 * <p>
 * Key Scenarios:
 * <ul>
 *   <li>Successful limit update by the legitimate card owner</li>
 *   <li>BOLA (Broken Object-Level Authorization) prevention</li>
 *   <li>Validation of negative limits and malformed requests
 * </ul>
 *
 * @author  Madavan Babu
 * @since 2026
 * @see     VirtualCardRepository
 * @see     UserRepository
 * @see   AccountRepository
 * @see   VirtualCard
 * @see   CardLimitUpdateRequest
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
@Testcontainers
class CardUpdateLimitIntegrationTest {

    // =========================================================================
    // 1. INFRASTRUCTURE SETUP (Postgres + Redis)
    // =========================================================================

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.0-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Postgres Overrides (Force Hibernate Dialect for PESSIMISTIC_WRITE support)
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none"); // Schema should be managed by migration
        registry.add("spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation", () -> "true");

        // Redis Overrides (Connect to the random Testcontainer port)
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private VirtualCardRepository virtualCardRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private RedisTemplate<String, Object> redisTemplate;

    private User testUser;
    private VirtualCard testCard;
    private UUID testUserId;

    /**
     * Resets the test environment to a pristine state before every test.
     * <p>
     * This method ensures complete isolation between integration tests by:
     * <ul>
     *   <li>Flushing all Redis keys (rate limits, idempotency markers, etc.)</li>
     *   <li>Deleting all database rows in reverse FK order to avoid constraint violations</li>
     *   <li>Creating a fresh {@code User}, linked {@code Account}, and {@code VirtualCard}
     *       that are immediately flushed to the database and wired into the security context</li>
     * </ul>
     * <p>
     * The resulting entities ({@link #testUser}, {@link #testCard}) are fully managed JPA
     * instances and can be safely used throughout the test lifecycle.
     *
     * @see User
     * @see Account
     * @see VirtualCard
     */
    @BeforeEach
    void setup() {
        // A. CLEAR INFRASTRUCTURE STATE
        // Use the template's factory to flush all keys (Idempotency, Rate Limits, etc.)
        // This resolves the bean-not-found issue by using the template you already configured.
        redisTemplate.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();

        // B. CLEAR DB (Order is critical for Foreign Keys)
        virtualCardRepository.deleteAll();
        accountRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        // C. PROVISIONING (The "Managed Entity" Flow)
        // No @Transactional means we must use saveAndFlush to commit immediately
        User user = User.builder()
                .email("owner@opayque.com")
                .fullName("Card Owner")
                .password("hashed_pw")
                .role(Role.CUSTOMER)
                .build();
        testUser = userRepository.saveAndFlush(user);

        // Inject the REAL Entity into the Security Context for SecurityUtil
        mockSecurityContext(testUser);

        Account wallet = Account.builder()
                .user(testUser)
                .currencyCode("USD")
                .iban("US99OWNER")
                .build();
        Account savedWallet = accountRepository.saveAndFlush(wallet);

        testCard = virtualCardRepository.saveAndFlush(VirtualCard.builder()
                .account(savedWallet)
                .pan("1711030000001234")
                .cvv("enc_cvv")
                .expiryDate("12/30")
                .cardholderName("Card Owner")
                .status(CardStatus.ACTIVE)
                .monthlyLimit(new BigDecimal("1000.00"))
                .build());
    }

    /**
     * Replaces the current {@link org.springframework.security.core.context.SecurityContext} with a synthetic one that authenticates the supplied {@link User}.
     * <p>
     * This utility is used during integration tests to simulate an already-logged-in principal without requiring
     * a full OAuth or password flow.  After invocation, Spring Security filters and {@code @AuthenticationPrincipal}
     * method arguments resolve to the provided {@code user}.
     *
     * @param user the pre-existing {@link User} entity that will act as the authenticated principal; must not be {@code null}
     * @see SecurityContextHolder
     */
    private void mockSecurityContext(User user) {
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken auth =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        user, null, user.getAuthorities()
                );
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    }


    // =========================================================================
    // TEST CASE 1: HAPPY PATH (Full Stack)
    // =========================================================================

    /**
     * Validates the happy-path flow for updating a card’s monthly spending limit through the public REST API.
     * <p>
     * This test exercises the idempotent {@code PATCH /api/v1/cards/{id}/limit} endpoint with a valid
     * {@link CardLimitUpdateRequest}.  It asserts that:
     * <ul>
     *   <li>The HTTP response is 200 OK and contains the updated limit and status.</li>
     *   <li>The change is atomically persisted in the database and visible via {@link VirtualCardRepository}.</li>
     *   <li>The idempotency key is accepted without side effects on the first invocation.</li>
     * </ul>
     * <p>
     * The method relies on the security context established by {@link #setup()} and assumes the caller
     * is authorized to mutate the card owned by {@link #testUser}.
     *
     * @throws Exception propagated from {@code MockMvc} if the request fails or assertions do not match
     * @see VirtualCard
     * @see CardLimitUpdateRequest
     * @see com.opayque.api.card.controller.CardController
     */
    @Test
    @DisplayName("1. Happy Path: Update Limit via API -> Persisted in DB")
    // NOTE: In your real app, verify how @WithMockUser maps to the User Entity.
    // You might need a custom @WithUserDetails setup if you look up ID by email.
    // For now, we assume SecurityUtil resolves the principal correctly.
    void shouldUpdateLimitSuccessfully() throws Exception {
        BigDecimal newLimit = new BigDecimal("5000.00");
        CardLimitUpdateRequest request = new CardLimitUpdateRequest(newLimit);

        // Act
        mockMvc.perform(patch("/api/v1/cards/{id}/limit", testCard.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idemp-key-001")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyLimit").value(5000.00))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Assert DB State
        VirtualCard updatedCard = virtualCardRepository.findById(testCard.getId()).orElseThrow();
        assertThat(updatedCard.getMonthlyLimit()).isEqualByComparingTo(newLimit);
    }

    // =========================================================================
    // TEST CASE 2: BOLA / SECURITY
    // =========================================================================

    /**
     * Verifies that the REST endpoint {@code PATCH /api/v1/cards/{id}/limit} enforces
     * Business Object Level Authorization (BOLA) by rejecting attempts to update
     * a {@link VirtualCard} owned by a different user.
     *
     * <p>The test simulates an attacker-authenticated session and attempts to lower
     * the monthly limit of a card that belongs to the victim created in
     * {@link #setup()}.  The expected behaviour is:
     * <ul>
     *   <li>HTTP 403 Forbidden is returned immediately.</li>
     *   <li>The card’s limit remains unchanged in the database.</li>
     * </ul>
     *
     * @see VirtualCard
     * @see CardLimitUpdateRequest
     * @see com.opayque.api.card.controller.CardController
     */
    @Test
    @DisplayName("2. Security: BOLA - Attacker cannot update Victim's card")
    void shouldBlockUpdateFromDifferentUser() throws Exception {
        // 1. Setup Attacker
        User attacker = User.builder().email("attacker@opayque.com").fullName("Attacker").password("attacker_hashed_pw").role(Role.CUSTOMER).build();
        User savedAttacker = userRepository.saveAndFlush(attacker);

        // 2. Switch Security Context to Attacker
        mockSecurityContext(savedAttacker);

        CardLimitUpdateRequest request = new CardLimitUpdateRequest(new BigDecimal("0.00"));

        // 3. Act
        mockMvc.perform(patch("/api/v1/cards/{id}/limit", testCard.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "hack-key-001")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // Assert: Limit should be UNCHANGED
        VirtualCard freshCard = virtualCardRepository.findById(testCard.getId()).orElseThrow();
        assertThat(freshCard.getMonthlyLimit()).isEqualByComparingTo("1000.00");
    }

    // =========================================================================
    // TEST CASE 3: VALIDATION (Negative Limits)
    // =========================================================================

    /**
     * Verifies that the REST endpoint {@code PATCH /api/v1/cards/{id}/limit} rejects
     * requests carrying a negative spending limit.
     *
     * <p>The test submits a {@link CardLimitUpdateRequest} whose {@code newLimit} is
     * {@code -10.00}.  The expected behaviour is:
     * <ul>
     *   <li>HTTP 400 Bad Request is returned immediately.</li>
     *   <li>No database update occurs.</li>
     * </ul>
     *
     * @throws Exception propagated from {@code MockMvc} if the request fails or assertions
     *                 do not match
     * @see CardLimitUpdateRequest
     * @see com.opayque.api.card.controller.CardController
     */
    @Test
    @DisplayName("3. Validation: Reject Negative Limits")
    void shouldRejectNegativeLimit() throws Exception {
        CardLimitUpdateRequest request = new CardLimitUpdateRequest(new BigDecimal("-10.00"));

        mockMvc.perform(patch("/api/v1/cards/{id}/limit", testCard.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "fail-key-001")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest()); // 400 Bad Request
    }

    // =========================================================================
    // TEST CASE 4: IDEMPOTENCY REPLAY
    // =========================================================================

    /**
     * Verifies that the REST endpoint {@code PATCH /api/v1/cards/{id}/limit} enforces idempotency
     * by rejecting requests that reuse an already-processed idempotency key.
     *
     * <p>The test sends two identical requests carrying the same {@code Idempotency-Key} header.
     * The expected behaviour is:
     * <ul>
     *   <li>The first request returns HTTP 200 OK and applies the limit update.</li>
     *   <li>The second request returns HTTP 409 Conflict without side effects.</li>
     * </ul>
     *
     * <p>Conflict detection is handled by the idempotency middleware and surfaced through
     * {@link GlobalExceptionHandler}.
     *
     * @throws Exception propagated from {@code MockMvc} if the request fails or assertions
     *                 do not match
     * @see CardLimitUpdateRequest
     * @see VirtualCard
     * @see GlobalExceptionHandler
     */
    @Test
    @DisplayName("4. Idempotency: Duplicate Key should cause Conflict")
    void shouldRejectDuplicateIdempotencyKey() throws Exception {
        CardLimitUpdateRequest request = new CardLimitUpdateRequest(new BigDecimal("2000.00"));
        String key = "replay-key-001";

        // 1st Call: Success
        mockMvc.perform(patch("/api/v1/cards/{id}/limit", testCard.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        // 2nd Call: Conflict (Replay)
        mockMvc.perform(patch("/api/v1/cards/{id}/limit", testCard.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict()); // 409 Conflict (Handled by GlobalExceptionHandler)
    }
}