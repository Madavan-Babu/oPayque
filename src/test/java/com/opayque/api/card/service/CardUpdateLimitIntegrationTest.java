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
 * End-to-End Integration Test for the Card Limit Management feature.
 * <p>
 * Validates the full lifecycle:
 * Controller (Validation) -> Security (BOLA) -> Service (Idempotency/RateLimit) -> DB (Persistence).
 * <p>
 * <b>Infrastructure:</b> Spins up both Postgres (Data) and Redis (Idempotency/RateLimit)
 * via Testcontainers to ensure a production-like environment.
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
     * Seamlessly injects the actual JPA Entity into the Security Context.
     * This ensures SecurityUtil.getCurrentUserId() finds the real ID and Type.
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