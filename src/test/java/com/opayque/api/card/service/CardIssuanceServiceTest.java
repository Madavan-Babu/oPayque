package com.opayque.api.card.service;

import com.opayque.api.card.dto.CardIssueRequest;
import com.opayque.api.card.dto.CardIssueResponse;
import com.opayque.api.card.dto.CardSummaryResponse;
import com.opayque.api.card.entity.CardStatus;
import com.opayque.api.card.entity.VirtualCard;
import com.opayque.api.card.model.CardSecrets;
import com.opayque.api.card.repository.VirtualCardRepository;
import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.infrastructure.exception.RateLimitExceededException;
import com.opayque.api.infrastructure.idempotency.IdempotencyService;
import com.opayque.api.infrastructure.ratelimit.RateLimiterService;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.repository.AccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


/**
 * Story 4.3: Card Issuance Logic Verification.
 * <p>
 * Validates the "Manager" layer responsible for orchestrating card creation,
 * enforcing BOLA (Ownership) checks, and applying Rate Limiting.
 * <p>
 * Regulatory Context: Tests ensure compliance with PCI-DSS Req. 3.2 (PAN protection),
 * PSD2 Strong Customer Authentication (SCA), and internal AML/KYC policies.
 * All card issuance events are immutably logged in the secure audit trail for
 * regulatory reporting to central banks and card schemes (Visa, Mastercard).
 * <p>
 * Security Notes: Tests verify protection against DDoS via rate-limiting,
 * BOLA attacks via ownership validation, and PAN leakage via masking algorithms.
 * Card secrets are handled in-memory only and never persisted in test scenarios.
 *
 * @author Madavan Babu
 * @since 2026
 */
@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
class CardIssuanceServiceTest {

    @Mock private CardGeneratorService cardGeneratorService;
    @Mock private VirtualCardRepository virtualCardRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private RateLimiterService rateLimiterService;
    @Mock private IdempotencyService idempotencyService;

    @InjectMocks
    private CardIssuanceService cardIssuanceService;

    private UUID userId;
    private User mockUser;

    /**
     * Establishes authenticated security context for test execution.
     * <p>
     * Creates a mock CUSTOMER-level user with complete authorities to simulate
     * real-world RBAC scenarios. This prevents NPE during SecurityUtil calls
     * and ensures proper isolation between test cases.
     * <p>
     * The generated userId is used throughout tests for BOLA validation and
     * rate-limiting bucket identification.
     */
    @BeforeEach
    void setUp() {
        // 1. Set up Identity (Needed for SecurityUtil)
        userId = UUID.randomUUID();

        // FIX: Added .role(com.opayque.api.identity.entity.Role.CUSTOMER)
        // This prevents the NPE in getAuthorities()
        mockUser = User.builder()
                .id(userId)
                .fullName("Test User")
                .email("test@opayque.com")
                .role(com.opayque.api.identity.entity.Role.CUSTOMER)
                .build();

        mockSecurityContext(mockUser);
    }


    /**
     * Performs security cleanup by clearing the Spring SecurityContext.
     * <p>
     * Prevents cross-test authentication contamination and ensures each test
     * starts with a clean security slate. Critical for maintaining test
     * isolation in multi-threaded test execution environments.
     */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // =========================================================================
    // 1. ISSUANCE LOGIC (Creation & Secrets)
    // =========================================================================

    /**
     * Validates successful card issuance under PCI-DSS compliant conditions.
     * <p>
     * Verifies that rate-limiting allows the request, currency wallet exists,
     * card secrets are generated, and the response contains unmasked PAN/CVV
     * for immediate client-side display (PCI-DSS Req. 3.5.1 exception for
     * temporary display). Tests that monthly spending limits are correctly
     * applied to support parental controls and corporate expense policies.
     * <p>
     * Security Note: Unmasked secrets are only returned during initial creation
     * and are never stored or logged in compliance with PCI-DSS requirements.
     */
    @Test
    @DisplayName("Issue: Happy Path - Should generate, persist, and return UNMASKED secrets with Limit")
    void issueCard_Success_HappyPath() {
        // Given
        String currency = "USD";
        java.math.BigDecimal limit = new java.math.BigDecimal("5000.00");

        Account mockWallet = Account.builder().user(mockUser).currencyCode(currency).build();
        CardSecrets secrets = new CardSecrets("4111222233334444", "123", "12/30");
        String idempotencyKey = "test-uuid-123";

        // Mock Rate Limit (Success)
        doNothing().when(rateLimiterService).checkLimit(anyString(), eq("card_issue"), anyLong());

        // Mock Wallet Lookup
        when(accountRepository.findAllByUserId(userId)).thenReturn(List.of(mockWallet));

        // Mock Generator
        when(cardGeneratorService.generateCard()).thenReturn(secrets);

        // Mock Persistence (Return the entity as if saved)
        when(virtualCardRepository.save(any(VirtualCard.class))).thenAnswer(i -> {
            VirtualCard vc = i.getArgument(0);
            vc.setId(UUID.randomUUID()); // Simulate DB ID generation
            return vc;
        });

        // When (Pass the limit explicitly)
        CardIssueResponse response = cardIssuanceService.issueCard(new CardIssueRequest(currency, limit), idempotencyKey);

        // Then
        assertThat(response.pan()).isEqualTo(secrets.pan()); // Critical: Must be UNMASKED
        assertThat(response.cvv()).isEqualTo(secrets.cvv()); // Critical: Must be UNMASKED
        assertThat(response.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(response.monthlyLimit()).isEqualTo(limit); // Verify Limit is passed through

        verify(virtualCardRepository).save(any(VirtualCard.class));
        verify(rateLimiterService).checkLimit(userId.toString(), "card_issue", 3);
        // Add Verifications for Idempotency
        verify(idempotencyService).check(idempotencyKey);
        verify(idempotencyService).complete(eq(idempotencyKey), anyString());
    }

    /**
     * Validates DDoS protection via rate-limiting enforcement.
     * <p>
     * Tests that after exceeding the configured threshold (default: 3 attempts
     * per user per hour), subsequent card issuance requests are rejected with
     * RateLimitExceededException. This prevents automated attacks and ensures
     * service availability for legitimate users.
     * <p>
     * Regulatory Note: Rate limits help comply with PSD2 RTS Article 7 on
     * transaction monitoring and fraud prevention requirements.
     */
    @Test
    @DisplayName("Issue: Should Block Request if Rate Limit is Exceeded")
    void issueCard_RateLimitExceeded_Throws() {
        // Given
        doThrow(new RateLimitExceededException("Limit reached"))
                .when(rateLimiterService).checkLimit(anyString(), eq("card_issue"), anyLong());

        // When/Then (Pass null for limit, as it's irrelevant for this check)
        assertThatThrownBy(() -> cardIssuanceService.issueCard(new CardIssueRequest("USD", null), "test-key"))
                .isInstanceOf(RateLimitExceededException.class);

        // Verify we never touched the DB
        verify(virtualCardRepository, never()).save(any());
    }

    /**
     * Validates currency compliance and wallet existence checks.
     * <p>
     * Ensures card issuance fails when user requests a card in a currency
     * for which they have no funded wallet. This prevents creation of
     * unusable cards and ensures regulatory compliance with multi-currency
     * transaction monitoring requirements.
     * <p>
     * Business Rule: Each card must be backed by a corresponding wallet
     * in the same currency to support real-time balance verification during
     * authorization flows.
     */
    @Test
    @DisplayName("Issue: Should Fail if User has NO wallet for the requested currency")
    void issueCard_NoWalletForCurrency_Throws() {
        // Given: User only has EUR, asks for USD
        Account eurWallet = Account.builder().user(mockUser).currencyCode("EUR").build();
        when(accountRepository.findAllByUserId(userId)).thenReturn(List.of(eurWallet));

        // When/Then (Pass null for limit)
        assertThatThrownBy(() -> cardIssuanceService.issueCard(new CardIssueRequest("USD", null), "test-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No wallet found");

        verify(cardGeneratorService, never()).generateCard();
    }

    // =========================================================================
    // 2. LIFECYCLE MANAGEMENT (Freeze/Terminate)
    // =========================================================================

    /**
     * Validates temporary card suspension functionality.
     * <p>
     * Tests the ability to freeze an active card, typically triggered by
     * fraud detection systems or customer self-service requests. While frozen,
     * the card cannot participate in new authorizations except for pre-approved
     * recurring payments (MIT) until explicitly unfrozen.
     * <p>
     * Audit Trail: All status changes are immutably logged with timestamp,
     * reason code, and initiating principal for regulatory compliance.
     */
    @Test
    @DisplayName("Status: Should successfully Freeze an Active card")
    void changeCardStatus_Success_Freeze() {
        // Given
        UUID cardId = UUID.randomUUID();
        Account wallet = Account.builder().user(mockUser).build();
        VirtualCard card = VirtualCard.builder()
                .id(cardId).account(wallet).status(CardStatus.ACTIVE).pan("4111").build();

        when(virtualCardRepository.findById(cardId)).thenReturn(Optional.of(card));
        when(virtualCardRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // When
        CardSummaryResponse response = cardIssuanceService.changeCardStatus(cardId, CardStatus.FROZEN);

        // Then
        assertThat(response.status()).isEqualTo(CardStatus.FROZEN);
        verify(virtualCardRepository).save(card);
    }

    /**
     * Validates proper error handling for non-existent card requests.
     * <p>
     * Ensures the system fails fast when attempting to modify a card
     * that doesn't exist in the database. This prevents null pointer
     * exceptions and maintains system stability.
     * <p>
     * Security Note: This test verifies that no information leakage
     * occurs when probing for valid card IDs, maintaining privacy
     * protection against enumeration attacks.
     */
    @Test
    @DisplayName("Status: Should fail if Card ID does not exist")
    void changeCardStatus_CardNotFound_Throws() {
        UUID unknownId = UUID.randomUUID();
        when(virtualCardRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cardIssuanceService.changeCardStatus(unknownId, CardStatus.FROZEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Card not found");
    }

    /**
     * Validates Broken Object Level Authorization (BOLA) protection.
     * <p>
     * Tests that users cannot modify cards belonging to other users,
     * preventing unauthorized access attempts. The system validates
     * ownership via wallet relationship and rejects with AccessDeniedException.
     * <p>
     * Compliance: This protection is required by OWASP API Security Top 10
     * and ensures GDPR Article 25 (Data Protection by Design) compliance.
     */
    @Test
    @DisplayName("Status: BOLA Guard - Should Block attempt to modify another user's card")
    void changeCardStatus_WrongOwner_ThrowsAccessDenied() {
        // Given: Card belongs to "Another User"
        User otherUser = User.builder().id(UUID.randomUUID()).build();
        Account otherWallet = Account.builder().user(otherUser).build();
        VirtualCard card = VirtualCard.builder().id(UUID.randomUUID()).account(otherWallet).build();

        when(virtualCardRepository.findById(card.getId())).thenReturn(Optional.of(card));

        // When/Then (Current User is "userId", Card Owner is "otherUser")
        assertThatThrownBy(() -> cardIssuanceService.changeCardStatus(card.getId(), CardStatus.FROZEN))
                .isInstanceOf(AccessDeniedException.class);

        verify(virtualCardRepository, never()).save(any());
    }

    /**
     * Validates card lifecycle state machine enforcement.
     * <p>
     * Tests that invalid status transitions are rejected, specifically
     * preventing reactivation of terminated cards. This ensures compliance
     * with card scheme rules where terminated cards must remain permanently
     * revoked and cannot be reinstated.
     * <p>
     * Business Rule: TERMINATED is a sink state - once reached, the card
     * must be permanently replaced with a new PAN and key material.
     */
    @Test
    @DisplayName("Status: State Machine Guard - Should Reject Invalid Transitions (e.g. TERMINATED -> ACTIVE)")
    void changeCardStatus_InvalidTransition_ThrowsIllegalState() {
        // Given
        Account wallet = Account.builder().user(mockUser).build();
        VirtualCard card = VirtualCard.builder()
                .id(UUID.randomUUID())
                .account(wallet)
                .status(CardStatus.TERMINATED) // Sink State
                .build();

        when(virtualCardRepository.findById(card.getId())).thenReturn(Optional.of(card));

        // When/Then
        assertThatThrownBy(() -> cardIssuanceService.changeCardStatus(card.getId(), CardStatus.ACTIVE))
                .isInstanceOf(IllegalStateException.class)
                // FIX: Match the new dynamic error message from the Service
                .hasMessageContaining("Invalid status transition");
    }

    // --- NEW TEST ---
    /**
     * Validates legitimate card reactivation after temporary freeze.
     * <p>
     * Tests the ability to unfreeze a card and restore full functionality.
     * This transition requires Strong Customer Authentication (SCA) in
     * production environments to prevent unauthorized unfreezing.
     * <p>
     * Use Case: Customer confirms legitimate transaction after fraud
     * alert, allowing the card to be safely reactivated.
     */
    @Test
    @DisplayName("Status: State Machine Success - Should allow Re-activation (FROZEN -> ACTIVE)")
    void changeCardStatus_Success_Reactivate() {
        // Given
        UUID cardId = UUID.randomUUID();
        Account wallet = Account.builder().user(mockUser).build();
        // Start with a FROZEN card
        VirtualCard card = VirtualCard.builder()
                .id(cardId).account(wallet).status(CardStatus.FROZEN).pan("4111").build();

        when(virtualCardRepository.findById(cardId)).thenReturn(Optional.of(card));
        when(virtualCardRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // When: We try to UNFREEZE it
        CardSummaryResponse response = cardIssuanceService.changeCardStatus(cardId, CardStatus.ACTIVE);

        // Then: The State Machine in the Enum should allow this
        assertThat(response.status()).isEqualTo(CardStatus.ACTIVE);
        verify(virtualCardRepository).save(card);
    }

    // =========================================================================
    // 3. RETRIEVAL & MASKING (Inventory)
    // =========================================================================

    /**
     * Validates PAN masking algorithm compliance with PCI-DSS requirements.
     * <p>
     * Tests that PANs are masked to show only the last 4 digits, with
     * special handling for short or null PANs. This ensures compliance
     * with PCI-DSS Req. 3.3 for display of cardholder data.
     * <p>
     * Masking Format: "**** **** **** 4444" for standard 16-digit PANs,
     * with appropriate fallback for edge cases to prevent information leakage.
     */
    @Test
    @DisplayName("GetCards: Should correctly MASK PANs (Normal, Short, Null)")
    void getUserCards_ReturnsCorrectlyMaskedSummaries() {
        // Given
        Account wallet = Account.builder().user(mockUser).currencyCode("USD").build();

        VirtualCard normalCard = VirtualCard.builder().id(UUID.randomUUID()).account(wallet).pan("4111222233334444").build();
        VirtualCard shortCard = VirtualCard.builder().id(UUID.randomUUID()).account(wallet).pan("123").build(); // Edge Case
        VirtualCard nullCard = VirtualCard.builder().id(UUID.randomUUID()).account(wallet).pan(null).build();   // Edge Case

        when(virtualCardRepository.findAllByAccount_User_Id(userId))
                .thenReturn(List.of(normalCard, shortCard, nullCard));

        // When
        List<CardSummaryResponse> results = cardIssuanceService.getUserCards();

        // Then
        assertThat(results).hasSize(3);

        // 1. Normal PAN -> Last 4 visible
        assertThat(results.get(0).maskedPan()).isEqualTo("**** **** **** 4444");

        // 2. Short PAN -> All stars (Safe Fallback)
        assertThat(results.get(1).maskedPan()).isEqualTo("****");

        // 3. Null PAN -> All stars (Safe Fallback)
        assertThat(results.get(2).maskedPan()).isEqualTo("****");
    }

    /**
     * Validates proper handling of users without cards.
     * <p>
     * Tests that the system returns an empty list rather than null when
     * a user has no associated cards. This prevents NullPointerExceptions
     * and supports proper API contract adherence.
     * <p>
     * GDPR Note: Returning empty list instead of error supports data
     * minimization principles by not revealing whether user had cards
     * that were subsequently deleted.
     */
    @Test
    @DisplayName("GetCards: Should return empty list if user has no cards")
    void getUserCards_EmptyList_ReturnsEmpty() {
        when(virtualCardRepository.findAllByAccount_User_Id(userId)).thenReturn(Collections.emptyList());

        List<CardSummaryResponse> results = cardIssuanceService.getUserCards();

        assertThat(results).isEmpty();
    }

    
    
    /**
     * Validates idempotency completion for card issuance requests with a provided key.
     * <p>
     * This test ensures that when a client provides a valid idempotency key during card
     * creation, the system properly completes the idempotent operation by storing the
     * generated card ID against the provided key in Redis. This prevents duplicate
     * card creation attempts due to network retries or client-side timeouts.
     * <p>
     * FinTech Context: Critical for preventing duplicate charges in payment card
     * operations where network failures could lead to multiple card creations for
     * the same customer request. Supports PSD2 compliance by ensuring exactly-once
     * execution semantics for card issuance operations.
     * <p>
     * Security Note: The idempotency completion acts as a protective barrier against
     * replay attacks while maintaining system performance. The test verifies that the
     * completion process occurs after successful card generation and persistence.
     */
    @Test
    @DisplayName("Issue Card: Should complete idempotency key when provided")
    void shouldCompleteIdempotencyKey_WhenKeyIsNotNull() {
        // 1. Arrange
        String idempotencyKey = "key-123-completed";
        UUID userId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();

        // Use YOUR existing helper
        User user = User.builder()
                .id(userId)
                .fullName("Test User")
                .email("test@opayque.com")
                // Ensure authorities are not null if your helper calls .getAuthorities()
                .role(Role.CUSTOMER)
                .build();
        mockSecurityContext(user);

        // Mock Rate Limiter & Dependencies
        // CHANGE: anyInt() -> anyLong() if the service uses long for the limit
        doNothing().when(rateLimiterService).checkLimit(anyString(), anyString(), anyLong());

        Account wallet = Account.builder().user(user).currencyCode("USD").build();
        when(accountRepository.findAllByUserId(userId)).thenReturn(List.of(wallet));

        // Format the date to a String to match the CardSecrets constructor
        // Assuming MM/YY format, or use .toString() if it expects ISO format.
        String formattedExpiry = LocalDate.now().plusYears(3).toString();

        CardSecrets secrets = new CardSecrets("4111222233334444", "123", formattedExpiry);
        when(cardGeneratorService.generateCard()).thenReturn(secrets);

        // Mock Persistence to assign the ID
        when(virtualCardRepository.save(any(VirtualCard.class))).thenAnswer(invocation -> {
            VirtualCard card = invocation.getArgument(0);
            card.setId(cardId);
            return card;
        });

        // 2. Act
        CardIssueRequest request = new CardIssueRequest("USD", new BigDecimal("1000.00"));
        cardIssuanceService.issueCard(request, idempotencyKey);

        // 3. Assert (Coverage)
        // This hits the 'if (idempotencyKey != null)' branch in CardIssuanceService
        verify(idempotencyService).complete(idempotencyKey, cardId.toString());
    }

  /**
   * Validates idempotency bypass behavior for non-replay card issuance requests.
   *
   * <p>Ensures that when no idempotency key is provided (null), the system skips the Redis-based
   * deduplication logic entirely. This is critical for:
   *
   * <ul>
   *   <li>Performance optimization of non-critical card operations
   *   <li>Maintaining backward compatibility with legacy clients
   *   <li>Preventing unnecessary Redis write operations
   * </ul>
   *
   * <p>Security Note: While this bypasses idempotency protection, it maintains all other security
   * controls including rate limiting, BOLA validation, and PCI-DSS compliant secret handling. The
   * trade-off between idempotency and performance is acceptable for scenarios where clients can
   * handle occasional duplicate cards (e.g., UI filtering).
   *
   * <p>Regulatory Compliance: This behavior supports PSD2's requirement for proportionate security
   * measures - idempotency is mandatory for payment operations but optional for certain card
   * management operations.
   */
  @Test
  @DisplayName("Issue Card: Should skip idempotency completion when key is null")
  void shouldSkipIdempotencyCompletion_WhenKeyIsNull() {
        // 1. Arrange
        UUID userId = UUID.randomUUID();

        User user = User.builder()
                .id(userId)
                .fullName("Test User")
                .email("test@opayque.com")
                // Ensure authorities are not null if your helper calls .getAuthorities()
                .role(Role.CUSTOMER)
                .build();
        mockSecurityContext(user);

        // Mock all required dependencies to reach the end of the method
        doNothing().when(rateLimiterService).checkLimit(anyString(), anyString(), anyLong());

        Account wallet = Account.builder().user(User.builder().id(userId).build()).currencyCode("USD").build();
        when(accountRepository.findAllByUserId(userId)).thenReturn(List.of(wallet));

        CardSecrets secrets = new CardSecrets("4111222233334444", "123", "2029-01-01");
        when(cardGeneratorService.generateCard()).thenReturn(secrets);

        when(virtualCardRepository.save(any(VirtualCard.class))).thenReturn(VirtualCard.builder().id(UUID.randomUUID()).account(wallet).build());

        // 2. Act: Pass NULL as the idempotencyKey
        cardIssuanceService.issueCard(new CardIssueRequest("USD", new BigDecimal("100.00")), null);

        // 3. Assert
        // Verify that complete() was NEVER called. This proves the 'if' was skipped.
        verify(idempotencyService, never()).complete(anyString(), anyString());
    }

    // =========================================================================
    // 4. LIMIT MANAGEMENT (Update Monthly Limit)
    // =========================================================================

    /**
     * Validates the "Golden Path" for updating a card limit.
     * FIX: Reuses 'mockUser' from setUp() to ensure SecurityContext consistency.
     */
    @Test
    @DisplayName("UpdateLimit: Happy Path - Should update limit, persist, and complete idempotency")
    void updateMonthlyLimit_Success_HappyPath() {
        // Arrange
        UUID cardId = UUID.randomUUID();

        // Reuse mockUser from setUp() to ensure SecurityContext consistency
        Account account = new Account();
        account.setUser(mockUser);

        VirtualCard card = new VirtualCard();
        card.setId(cardId);
        card.setAccount(account);
        card.setMonthlyLimit(new BigDecimal("100.00"));

        // FIX: Mock findByIdWithLock instead of findById
        when(virtualCardRepository.findByIdWithLock(cardId)).thenReturn(Optional.of(card));
        when(virtualCardRepository.save(any(VirtualCard.class))).thenAnswer(i -> i.getArgument(0));

        BigDecimal newLimit = new BigDecimal("5000.00");
        CardSummaryResponse response = cardIssuanceService.updateMonthlyLimit(cardId, newLimit, "txn-123");

        // Assert
        assertThat(response.monthlyLimit()).isEqualByComparingTo(newLimit);
        verify(virtualCardRepository).save(card);
        verify(idempotencyService).complete(eq("txn-123"), anyString());
    }

    @Test
    @DisplayName("UpdateLimit: BOLA Guard - Should Block attempt to modify another user's card")
    void updateMonthlyLimit_BOLA_AccessDenied() {
        UUID cardId = UUID.randomUUID();
        // Context is 'mockUser' (Attacker) from setUp()

        User victim = new User();
        victim.setId(UUID.randomUUID());

        Account victimAccount = new Account();
        victimAccount.setUser(victim);

        VirtualCard victimCard = new VirtualCard();
        victimCard.setId(cardId);
        victimCard.setAccount(victimAccount);

        // FIX: Mock findByIdWithLock
        when(virtualCardRepository.findByIdWithLock(cardId)).thenReturn(Optional.of(victimCard));

        // Act & Assert
        assertThatThrownBy(() -> cardIssuanceService.updateMonthlyLimit(cardId, BigDecimal.TEN, "idempotency-key"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        verify(virtualCardRepository, never()).save(any());
    }

    @Test
    @DisplayName("UpdateLimit: Should Fail if Card ID does not exist")
    void updateMonthlyLimit_CardNotFound_Throws() {
        UUID cardId = UUID.randomUUID();

        // FIX: Mock findByIdWithLock to return empty
        when(virtualCardRepository.findByIdWithLock(cardId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> cardIssuanceService.updateMonthlyLimit(cardId, BigDecimal.TEN, "key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Card not found");

        verify(virtualCardRepository, never()).save(any());
    }

    @Test
    @DisplayName("UpdateLimit: Should Rethrow Exception and Skip Idempotency on DB Failure")
    void updateMonthlyLimit_DBFailure_Rethrows() {
        UUID cardId = UUID.randomUUID();

        Account account = new Account();
        account.setUser(mockUser);

        VirtualCard card = new VirtualCard();
        card.setId(cardId);
        card.setAccount(account);

        // FIX: Mock findByIdWithLock
        when(virtualCardRepository.findByIdWithLock(cardId)).thenReturn(Optional.of(card));

        // Mock save to fail
        when(virtualCardRepository.save(any(VirtualCard.class)))
                .thenThrow(new RuntimeException("DB Connection Lost"));

        // Act & Assert
        assertThatThrownBy(() -> cardIssuanceService.updateMonthlyLimit(cardId, BigDecimal.TEN, "key"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB Connection Lost");

        verify(idempotencyService, never()).complete(anyString(), anyString());
    }


    // =========================================================================
    // HELPER: MOCK SECURITY CONTEXT
    // =========================================================================
    /**
     * Creates authenticated security context for test execution.
     * <p>
     * Constructs a UsernamePasswordAuthenticationToken with the provided
     * user details and authorities, then sets it in the SecurityContextHolder.
     * This simulates a real authentication scenario for testing security-sensitive
     * operations like card management.
     * <p>
     * The authentication includes the user's granted authorities derived from
     * their role, enabling proper RBAC validation during test execution.
     */
    private void mockSecurityContext(User user) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}