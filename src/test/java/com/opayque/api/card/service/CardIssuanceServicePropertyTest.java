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
import com.opayque.api.infrastructure.idempotency.IdempotencyService;
import com.opayque.api.infrastructure.ratelimit.RateLimiterService;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.repository.AccountRepository;
import net.jqwik.api.*;
import org.junit.jupiter.api.AfterEach;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;


/**
 * Story 4.3: Property-Based Tests (PBT) for Card Issuance.
 * <p>
 * These tests generate hundreds of randomized inputs to prove strict invariants
 * regarding Security (BOLA), Data Integrity (Limits), and Safety (Masking).
 * <p>
 * Certifications: PCI-DSS v4.0, PSD2 RTS, ISO 27001 controls A.18.2.3 (Cryptographic
 * key management) & A.9.4.2 (Secure card-holder data).
 * <p>
 * Invariants verified:
 * <ul>
 *   <li>Wallet Mismatch – Request currency must match an existing customer wallet.</li>
 *   <li>Monthly Limit – Persisted value must equal the requested value (null allowed).</li>
 *   <li>BOLA – Only card owner can mutate lifecycle status.</li>
 *   <li>Terminated Immutability – TERMINATED cards cannot transition to any other state.</li>
 *   <li>PAN Masking – Never reveal more than the last four digits.</li>
 * </ul>
 *
 * @author Madavan Babu
 * @since 2026
 */
@ActiveProfiles("test")
class CardIssuanceServicePropertyTest {

    // Mocks
    private final CardGeneratorService cardGeneratorService = mock(CardGeneratorService.class);
    private final VirtualCardRepository virtualCardRepository = mock(VirtualCardRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final RateLimiterService rateLimiterService = mock(RateLimiterService.class);
    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);

    // Subject
    private final CardIssuanceService service = new CardIssuanceService(
            cardGeneratorService,
            virtualCardRepository,
            accountRepository,
            rateLimiterService,
            idempotencyService
    );

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // =========================================================================
    // PROPERTY 1: GATEKEEPER (Wallet Mismatch)
    // Invariant: If user requests currency X but has NO wallet for X, it fails.
    // =========================================================================
    /**
     * Property 1 – Wallet Currency Mismatch Gatekeeper.
     * <p>
     * Pre-condition: Customer wallet currency ≠ requested card currency.
     * <p>
     * Post-condition: {@link IllegalArgumentException} is thrown with “No wallet found” message.
     * <p>
     * Business: Prevents issuance of cards in unsupported currencies (FX risk & AML).
     */
    @Property
    void issueCard_WalletMismatch_Invariant(
            @ForAll("currencies") String requestedCurrency,
            @ForAll("currencies") String existingWalletCurrency
    ) {
        // Constraint: Ensure the wallet they have is DIFFERENT from what they ask for.
        Assume.that(!requestedCurrency.equals(existingWalletCurrency));

        UUID userId = UUID.randomUUID();
        mockCurrentUser(userId);

        // Given: User has a wallet in 'existingWalletCurrency'
        Account existingWallet = Account.builder().currencyCode(existingWalletCurrency).build();
        when(accountRepository.findAllByUserId(userId)).thenReturn(List.of(existingWallet));

        // When/Then: User asks for 'requestedCurrency' -> Should Fail
        assertThatThrownBy(() -> service.issueCard(new CardIssueRequest(requestedCurrency, null), UUID.randomUUID().toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No wallet found");
    }

    // =========================================================================
    // PROPERTY 2: DATA INTEGRITY (Monthly Limits)
    // Invariant: The persisted limit MUST match the requested limit (Nulls included).
    // =========================================================================
    /**
     * Property 2 – Monthly Limit Data Integrity.
     * <p>
     * Pre-condition: Valid wallet & card secrets.
     * <p>
     * Post-condition: Response and persisted entity contain the exact requested limit (null allowed).
     * <p>
     * Business: Guarantees that customer-defined spend controls are honoured without drift.
     */
    @Property
    void issueCard_MonthlyLimit_Invariant(
            @ForAll("bigDecimalsWithNull") BigDecimal randomLimit
    ) {
        // FIX 1: Reset Mocks per iteration to prevent "TooManyActualInvocations"
        reset(virtualCardRepository, accountRepository, cardGeneratorService);

        UUID userId = UUID.randomUUID();
        String currency = "EUR"; // Changed from USD to EUR per requirement
        mockCurrentUser(userId);

        // Given: Valid Wallet & Secrets
        Account wallet = Account.builder().user(User.builder().fullName("Test").build()).currencyCode(currency).build();
        when(accountRepository.findAllByUserId(userId)).thenReturn(List.of(wallet));
        when(cardGeneratorService.generateCard()).thenReturn(new CardSecrets("1234", "123", "12/99"));

        // Mock Save to capture the entity
        when(virtualCardRepository.save(any(VirtualCard.class))).thenAnswer(i -> {
            VirtualCard vc = i.getArgument(0);
            vc.setId(UUID.randomUUID());
            return vc;
        });

        // When: Issue with random limit
        CardIssueResponse response = service.issueCard(new CardIssueRequest(currency, randomLimit), UUID.randomUUID().toString());

        // Then: The response (and thus the entity) must match exactly
        assertThat(response.monthlyLimit()).isEqualTo(randomLimit);

        // Verify Persistence Capture
        ArgumentCaptor<VirtualCard> captor = ArgumentCaptor.forClass(VirtualCard.class);
        verify(virtualCardRepository).save(captor.capture());
        assertThat(captor.getValue().getMonthlyLimit()).isEqualTo(randomLimit);
    }

    // =========================================================================
    // PROPERTY 3: SECURITY (BOLA)
    // Invariant: If Requester != Owner, AccessDeniedException is ALWAYS thrown.
    // =========================================================================
    /**
     * Property 3 – Broken Object Level Authorisation (BOLA) Defence.
     * <p>
     * Pre-condition: Requester ID ≠ card owner ID.
     * <p>
     * Post-condition: {@link AccessDeniedException} is thrown; no status change occurs.
     * <p>
     * Business: Enforces the PSD2 “strong customer authentication” principle on lifecycle ops.
     */
    @Property
    void changeCardStatus_BOLA_Invariant(
            @ForAll("uuids") UUID requesterId,
            @ForAll("uuids") UUID ownerId,
            @ForAll CardStatus newStatus
    ) {
        // Constraint: Requester is NOT the owner
        Assume.that(!requesterId.equals(ownerId));

        mockCurrentUser(requesterId);
        UUID cardId = UUID.randomUUID();

        // Given: Card belongs to 'ownerId'
        User owner = User.builder().id(ownerId).build();
        Account wallet = Account.builder().user(owner).build();
        VirtualCard card = VirtualCard.builder().id(cardId).account(wallet).status(CardStatus.ACTIVE).build();

        when(virtualCardRepository.findById(cardId)).thenReturn(Optional.of(card));

        // When/Then
        assertThatThrownBy(() -> service.changeCardStatus(cardId, newStatus))
                .isInstanceOf(AccessDeniedException.class);
    }

    // =========================================================================
    // PROPERTY 4: LIFECYCLE (Terminated is Immutable)
    // Invariant: If card is TERMINATED, ANY status change throws IllegalStateException.
    // =========================================================================
    /**
     * Property 4 – Lifecycle Immutability for TERMINATED Cards.
     * <p>
     * Pre-condition: Card status = TERMINATED and target status ≠ TERMINATED.
     * <p>
     * Post-condition: {@link IllegalStateException} is thrown; status remains TERMINATED.
     * <p>
     * Business: Ensures PCI-DSS requirement 3.2.1 (irrevocable PAN revocation).
     */
    @Property
    void changeCardStatus_Lifecycle_Invariant(
            @ForAll CardStatus targetStatus
    ) {
        // FIX 2: Ignore "Identity" transition (Terminated -> Terminated).
        // That is a valid no-op. We only want to ensure it cannot become ACTIVE/FROZEN.
        Assume.that(targetStatus != CardStatus.TERMINATED);

        // FIX 1: Reset Mocks per iteration
        reset(virtualCardRepository);

        UUID userId = UUID.randomUUID();
        mockCurrentUser(userId); // BOLA passes (User is an owner)

        UUID cardId = UUID.randomUUID();
        User owner = User.builder().id(userId).build();
        Account wallet = Account.builder().user(owner).build();

        // Given: Card is TERMINATED
        VirtualCard deadCard = VirtualCard.builder()
                .id(cardId).account(wallet).status(CardStatus.TERMINATED).build();

        when(virtualCardRepository.findById(cardId)).thenReturn(Optional.of(deadCard));

        // When/Then
        assertThatThrownBy(() -> service.changeCardStatus(cardId, targetStatus))
                .isInstanceOf(IllegalStateException.class);
    }

    // =========================================================================
    // PROPERTY 5: SAFETY (PAN Masking)
    // Invariant: Masked PAN never exposes more than 4 characters.
    // =========================================================================
    /**
     * Property 5 – PAN Masking Safety.
     * <p>
     * Pre-condition: Any PAN (null, empty, short, valid length).
     * <p>
     * Post-condition: Masked PAN reveals ≤ 4 characters; last 4 match raw PAN when present.
     * <p>
     * Business: PCI-DSS 3.3 (mask PAN when displayed).
     */
    @Property
    void panMasking_Safety_Invariant(
            @ForAll("panStrings") String rawPan
    ) {
        UUID userId = UUID.randomUUID();
        mockCurrentUser(userId);

        // Given: A card with a random PAN (could be null, empty, short, long)
        VirtualCard card = VirtualCard.builder()
                .id(UUID.randomUUID())
                .pan(rawPan)
                .account(Account.builder().currencyCode("USD").build()) // Stub required fields
                .build();

        when(virtualCardRepository.findAllByAccount_User_Id(userId)).thenReturn(List.of(card));

        // When
        List<CardSummaryResponse> results = service.getUserCards();
        String masked = results.get(0).maskedPan();

        // Then: Safety Invariants
        // 1. Must not be null
        assertThat(masked).isNotNull();

        // 2. Must only contain asterisks or the last 4 characters
        String visiblePart = masked.replace("*", "").replace(" ", "");
        assertThat(visiblePart.length()).isLessThanOrEqualTo(4);

        // 3. If raw PAN is long enough, the last 4 must-match
        if (rawPan != null && rawPan.length() >= 4) {
            String last4Raw = rawPan.substring(rawPan.length() - 4);
            assertThat(masked).endsWith(last4Raw);
        }
    }

    // =========================================================================
    // GENERATORS & HELPERS
    // =========================================================================

    /**
     * Generator for 3-letter ISO-4217-like currency codes.
     * <p>
     * Sample outputs: “EUR”, “USD”, “ZAR”.
     */
    @Provide
    Arbitrary<String> currencies() {
        return Arbitraries.strings().withCharRange('A', 'Z').ofLength(3);
    }

    /**
     * Generator for monetary amounts with 2-decimal scale and 50 % null injection.
     * <p>
     * Range: [1, 10] – suitable for monthly limits in PBT.
     */
    @Provide
    Arbitrary<BigDecimal> bigDecimalsWithNull() {
        // Use 'between' instead of ofMin/ofMax
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ONE, BigDecimal.TEN)
                .ofScale(2) // Scale 2 for user facing numbers, Scale 4 for internal ledgers.
                .injectNull(0.5);
    }


    /**
     * Generator for random UUIDs.
     * <p>
     * Used to simulate arbitrary user or card identifiers.
     */
    @Provide
    Arbitrary<UUID> uuids() {
        return Arbitraries.randomValue(random -> UUID.randomUUID());
    }

    /**
     * Generator for PAN strings including edge cases (null, empty, short, long).
     * <p>
     * Sample outputs: null, “123”, “4111111111111111”.
     */
    @Provide
    Arbitrary<String> panStrings() {
        // Generates nulls, empty strings, short strings, and valid-length strings
        return Arbitraries.strings()
                .alpha()
                .numeric()
                .ofMinLength(0)
                .ofMaxLength(100) // 100 is sufficient to test the loop/logic
                .injectNull(0.1);
    }

    /**
     * Helper to inject a authenticated customer principal into Spring SecurityContext.
     * <p>
     * Authority: ROLE_CUSTOMER – matches production access control matrix.
     */
    private void mockCurrentUser(UUID userId) {
        User principal = User.builder().id(userId).email("pbt@opayque.com").role(Role.CUSTOMER).build();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}