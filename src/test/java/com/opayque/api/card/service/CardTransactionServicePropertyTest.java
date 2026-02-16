package com.opayque.api.card.service;

import com.opayque.api.card.controller.CardController;
import com.opayque.api.card.dto.CardTransactionRequest;
import com.opayque.api.card.entity.CardStatus;
import com.opayque.api.card.entity.VirtualCard;
import com.opayque.api.card.repository.VirtualCardRepository;
import com.opayque.api.infrastructure.encryption.AttributeEncryptor;
import com.opayque.api.infrastructure.exception.InsufficientFundsException;
import com.opayque.api.infrastructure.idempotency.IdempotencyService;
import com.opayque.api.infrastructure.ratelimit.RateLimiterService;
import com.opayque.api.transactions.controller.TransferController;
import com.opayque.api.wallet.dto.CreateLedgerEntryRequest;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.service.LedgerService;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


/**
 * <p>
 * Property‑based test suite that validates the contractual invariants of
 * {@link CardTransactionService}.  The class exercises a wide range of
 * scenarios using {@code jqwik} properties to ensure that security, state
 * management, and ledger integrity are consistently enforced.
 * </p>
 *
 * <p>
 * Each {@code @Property} method represents a high‑level business rule that
 * must hold for every generated input.  The tests are grouped by the
 * conceptual “invariant” they protect:
 * </p>
 * <ul>
 *   <li><b>Fortress</b> – invalid credentials (CVV/expiry) must always result in a
 *       {@link BadCredentialsException} and never affect the ledger.</li>
 *   <li><b>Status</b> – transactions on non‑active cards (frozen or terminated)
 *       must be denied outright.</li>
 *   <li><b>Ghost</b> – unknown PANs must trigger {@link BadCredentialsException}
 *       to prevent enumeration attacks.</li>
 *   <li><b>Ledger Integrity</b> – successful transactions must persist the exact
 *       financial amounts and description supplied in the request.</li>
 *   <li><b>Fail‑Fast Ordering</b> – security checks (e.g., CVV validation) must
 *       fail before status checks to avoid leaking card state.</li>
 *   <li><b>Atomic Accumulator</b> – updates to the spending accumulator must
 *       mirror ledger success, eliminating “ghost spending”.</li>
 *   <li><b>Deterministic Link</b> – the external transaction identifier must
 *       deterministically derive a stable reference UUID.</li>
 * </ul>
 *
 * <p>
 * The test harness creates a fresh {@link TestContext} for each execution via
 * {@link #setup()}.  Arbitrary data providers supply realistic yet varied
 * instances of {@link VirtualCard}, {@link CardTransactionRequest}, and PAN
 * strings, enabling exhaustive exploration of edge cases without hand‑crafted
 * fixtures.
 * </p>
 *
 * @author Madavan Babu
 * @since 2026
 *
 * @see CardTransactionService
 * @see CardController
 * @see VirtualCardRepository
 */
@Tag("pbt")
class CardTransactionServicePropertyTest {

    // Helper Record to hold Mocks
    private record TestContext(
            CardTransactionService service,
            VirtualCardRepository repo,
            CardLimitService limitService,
            LedgerService ledgerService,
            AttributeEncryptor encryptor,
            RateLimiterService rateLimiter,
            IdempotencyService idempotency
    ) {}

    private TestContext setup() {
        VirtualCardRepository repo = mock(VirtualCardRepository.class);
        CardLimitService limitService = mock(CardLimitService.class);
        LedgerService ledgerService = mock(LedgerService.class);
        AttributeEncryptor encryptor = mock(AttributeEncryptor.class);
        RateLimiterService rateLimiter = mock(RateLimiterService.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);

        CardTransactionService service = new CardTransactionService(
                repo, limitService, ledgerService, encryptor, rateLimiter, idempotency
        );

        return new TestContext(service, repo, limitService, ledgerService, encryptor, rateLimiter, idempotency);
    }

    // =========================================================================
    // PROPERTY 1: The "Fortress" Invariant (Security Gates)
    // =========================================================================


    /**
     * Validates that a transaction request with mismatched security credentials (CVV or expiry date)
     * always fails with a {@link BadCredentialsException} and that the {@link LedgerService} is never
     * invoked.
     *
     * <p>This property‑based test protects the ledger from being polluted by fraudulent attempts
     * where the supplied CVV or expiry date does not correspond to the stored values for an
     * active {@link VirtualCard}. By asserting that the service throws the appropriate security
     * exception before any ledger entry is recorded, it enforces a strict separation between
     * authentication failures and financial persistence.</p>
     *
     * @param card    a {@link VirtualCard} generated by the {@code validActiveCard} provider; it
     *                represents a card that is active, has a stored CVV and expiry date, and is
     *                persisted in the repository.
     * @param request a {@link CardTransactionRequest} generated by the {@code validRequest}
     *                provider; the request contains a PAN, CVV, and expiry date that are
     *                intentionally different from those stored on {@code card}.
     *
     * @see CardTransactionService
     * @see CardController
     * @see VirtualCardRepository
     * @see LedgerService
     */
    @Property
    @Label("1. Invalid Credentials (CVV/Expiry) MUST throw BadCredentialsException & Protect Ledger")
    void invalidCredentialsMustAlwaysFail(
            @ForAll("validActiveCard") VirtualCard card,
            @ForAll("validRequest") CardTransactionRequest request
    ) {
        // CHAOS: Ensure mismatch
        Assume.that(!request.getCvv().equals("123") || !request.getExpiryDate().equals("12/30"));

        TestContext ctx = setup();

        try (MockedStatic<AttributeEncryptor> staticMock = mockStatic(AttributeEncryptor.class)) {
            // Setup Blind Index
            staticMock.when(() -> AttributeEncryptor.blindIndex(request.getPan())).thenReturn("hashed_pan");
            when(ctx.repo.findByPanFingerprint("hashed_pan")).thenReturn(Optional.of(card));

            // Mock Decryption to return "True" values (which differ from Request)
            when(ctx.encryptor.convertToEntityAttribute(card.getCvv())).thenReturn("123");
            when(ctx.encryptor.convertToEntityAttribute(card.getExpiryDate())).thenReturn("12/30");

            // Assertion
            assertThatThrownBy(() -> ctx.service.processTransaction(request))
                    .isInstanceOf(BadCredentialsException.class);

            // CRITICAL: Ledger must NEVER be touched
            verify(ctx.ledgerService, never()).recordEntry(any());
        }
    }

    // =========================================================================
    // PROPERTY 2: The "Status" Invariant (Account State)
    // =========================================================================


    /**
     * <p>Ensures that any attempt to process a transaction using a non‑active
     * {@link VirtualCard} (e.g., frozen or terminated) results in an
     * {@link AccessDeniedException}.</p>
     *
     * <p>The test creates a {@link TestContext}, mocks {@link AttributeEncryptor}
     * to return a deterministic blind index for the PAN, and configures the
     * repository to return the supplied {@code card} when queried by that index.
     * It then verifies that {@link com.opayque.api.transactions.service.TransferService#transferFunds(UUID, String, String, String, String)}
     * throws an exception containing the card’s status.</p>
     *
     * @param card    the {@link VirtualCard} instance representing a non‑active card
     *                used in the test scenario
     * @param request the {@link CardTransactionRequest} containing the transaction
     *                details that will be processed against the {@code card}
     *
     * @see TransferController
     * @see CardTransactionService
     * @see VirtualCardRepository
     */
    @Property
    @Label("2. Non-Active Cards (Frozen/Terminated) MUST always be Denied")
    void nonActiveCardsMustAlwaysBeDenied(
            @ForAll("nonActiveCard") VirtualCard card,
            @ForAll("matchingRequest") CardTransactionRequest request
    ) {
        TestContext ctx = setup();

        try (MockedStatic<AttributeEncryptor> staticMock = mockStatic(AttributeEncryptor.class)) {
            staticMock.when(() -> AttributeEncryptor.blindIndex(request.getPan())).thenReturn("hashed_pan");
            when(ctx.repo.findByPanFingerprint("hashed_pan")).thenReturn(Optional.of(card));

            // Credentials match (so we pass the first gate)
            when(ctx.encryptor.convertToEntityAttribute(card.getCvv())).thenReturn(request.getCvv());
            when(ctx.encryptor.convertToEntityAttribute(card.getExpiryDate())).thenReturn(request.getExpiryDate());

            // Assertion
            assertThatThrownBy(() -> ctx.service.processTransaction(request))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining(card.getStatus().name());
        }
    }

    // =========================================================================
    // PROPERTY 3: The "Ghost" Invariant (Lookup Failure)
    // =========================================================================

    /**
     * <p>Verifies that a transaction request containing a PAN that is not stored in the
     * {@link VirtualCardRepository} causes the service to throw a
     * {@link BadCredentialsException}. This behavior prevents attackers from enumerating
     * valid PAN values by ensuring the response is indistinguishable from a generic
     * authentication failure.</p>
     *
     * <p>The test creates a {@link TestContext}, mocks {@link AttributeEncryptor} to return a
     * deterministic blind index for the supplied {@code pan}, and configures the repository to
     * return {@link Optional#empty()} for that index. When {@code ctx.service.processTransaction(request)}
     * is executed, the expected outcome is a {@link BadCredentialsException}.</p>
     *
     * @param pan a {@link String} generated by the {@code validPan} provider;
     *            it represents a syntactically valid but unknown PAN that must not be
     *            found in the data store.
     *
     * @see CardTransactionService
     * @see CardController
     * @see VirtualCardRepository
     * @see LedgerService
     */
    @Property
    @Label("3. Unknown PANs MUST throw BadCredentials (preventing Enumeration)")
    void unknownPansMustThrowBadCredentials(
            @ForAll("validPan") String pan
    ) {
        TestContext ctx = setup();
        CardTransactionRequest request = CardTransactionRequest.builder()
                .pan(pan).externalTransactionId("txn_1").merchantName("Test").build();

        try (MockedStatic<AttributeEncryptor> staticMock = mockStatic(AttributeEncryptor.class)) {
            staticMock.when(() -> AttributeEncryptor.blindIndex(pan)).thenReturn("unknown_hash");
            when(ctx.repo.findByPanFingerprint("unknown_hash")).thenReturn(Optional.empty());

            // Assertion: Must be BadCredentials, NOT "UserNotFound"
            assertThatThrownBy(() -> ctx.service.processTransaction(request))
                    .isInstanceOf(BadCredentialsException.class);
        }
    }

    // =========================================================================
    // PROPERTY 4: The "Ledger Integrity" Invariant (Golden Path)
    // =========================================================================

    /**
     * Validates that a processed transaction persists exact financial values and a correctly
     * formatted description in the ledger.<p>
     * The method exercises the full transaction flow: it mocks the encryption and lookup of a
     * {@link VirtualCard}, invokes the {@link CardTransactionService#processTransaction(CardTransactionRequest)}
     * operation, and then captures the {@link CreateLedgerEntryRequest} sent to the
     * {@link LedgerService}. The captured request is asserted to contain an amount and currency
     * that match the original {@link CardTransactionRequest}, and a description that follows the
     * pattern “POS: &lt;merchantName&gt; | MCC: &lt;merchantCategoryCode&gt;”.<p>
     * This ensures financial integrity and compliance with reporting standards for every valid
     * active card transaction.
     *
     * @param card the {@link VirtualCard} representing an active card used for the transaction.
     * @param request the {@link CardTransactionRequest} containing transaction details such as PAN,
     *                amount, currency, merchant name, and optional merchant category code.
     *
     * @see CardController
     * @see VirtualCardRepository
     * @see LedgerService
     */
    @Property
    @Label("4. Valid Transactions MUST persist exact financials and correct Description")
    void validTransactionsMustPersistExactFinancials(
            @ForAll("validActiveCard") VirtualCard card,
            @ForAll("matchingRequest") CardTransactionRequest request
    ) {
        TestContext ctx = setup();

        try (MockedStatic<AttributeEncryptor> staticMock = mockStatic(AttributeEncryptor.class)) {
            staticMock.when(() -> AttributeEncryptor.blindIndex(request.getPan())).thenReturn("hashed_pan");
            when(ctx.repo.findByPanFingerprint("hashed_pan")).thenReturn(Optional.of(card));

            // Pass Auth
            when(ctx.encryptor.convertToEntityAttribute(card.getCvv())).thenReturn(request.getCvv());
            when(ctx.encryptor.convertToEntityAttribute(card.getExpiryDate())).thenReturn(request.getExpiryDate());

            // Execution
            ctx.service.processTransaction(request);

            // Capture Ledger Request
            ArgumentCaptor<CreateLedgerEntryRequest> captor = ArgumentCaptor.forClass(CreateLedgerEntryRequest.class);
            verify(ctx.ledgerService).recordEntry(captor.capture());
            CreateLedgerEntryRequest ledgerReq = captor.getValue();

            // Assertions
            assertThat(ledgerReq.amount()).isEqualByComparingTo(request.getAmount());
            assertThat(ledgerReq.currency()).isEqualTo(request.getCurrency());

            // Regex Check for Description
            String expectedDesc = String.format("POS: %s | MCC: %s",
                    request.getMerchantName(),
                    request.getMerchantCategoryCode() == null ? "0000" : request.getMerchantCategoryCode());
            assertThat(ledgerReq.description()).isEqualTo(expectedDesc);
        }
    }

    // =========================================================================
    // PROPERTY 5: The "Fail-Fast" Ordering Invariant
    // =========================================================================

    /**
     * <p>
     * Validates that security checks (e.g., CVV verification) are evaluated and
     * fail before any status checks (such as a frozen card) are considered. This
     * ordering prevents an attacker from inferring that a correct CVV was supplied
     * simply because a {@code Card Frozen} error is returned, thereby reducing the
     * risk of credential leakage.
     * </p>
     *
     * <p>
     * The test simulates a scenario where a card is frozen and the request contains
     * an incorrect CVV. It mocks the encryption and repository layers to ensure the
     * service processes the request with the expected data and asserts that a
     * {@link BadCredentialsException} is thrown, confirming that the security check
     * fails prior to any business‑rule status evaluation.
     * </p>
     *
     * @param card    the {@link VirtualCard} instance representing a non‑active (frozen) card
     * @param request the {@link CardTransactionRequest} containing a valid request payload
     *
     * @see AttributeEncryptor
     * @see TestContext
     * @see CardTransactionService
     * @see VirtualCardRepository
     * @see CardController
     */
    @Property
    @Label("5. Security (CVV) MUST fail before Status (Frozen) to prevent Leakage")
    void securityChecksMustFailBeforeStatusChecks(
            @ForAll("nonActiveCard") VirtualCard card,
            @ForAll("validRequest") CardTransactionRequest request
    ) {
        // CHAOS: Card is Frozen AND CVV is Wrong
        Assume.that(!request.getCvv().equals("123")); // Request has wrong CVV

        TestContext ctx = setup();

        try (MockedStatic<AttributeEncryptor> staticMock = mockStatic(AttributeEncryptor.class)) {
            staticMock.when(() -> AttributeEncryptor.blindIndex(request.getPan())).thenReturn("hashed_pan");
            when(ctx.repo.findByPanFingerprint("hashed_pan")).thenReturn(Optional.of(card));

            // Mock DB having "123" (Real CVV), but Request has something else
            when(ctx.encryptor.convertToEntityAttribute(card.getCvv())).thenReturn("123");
            when(ctx.encryptor.convertToEntityAttribute(card.getExpiryDate())).thenReturn(request.getExpiryDate());

            // Assertion: MUST fail on Credentials (Security), NOT Status (Business Rule)
            // If it says "Card Frozen", hacker knows they guessed the CVV right.
            assertThatThrownBy(() -> ctx.service.processTransaction(request))
                    .isInstanceOf(BadCredentialsException.class);
        }
    }

    // =========================================================================
    // PROPERTY 6: The "Atomic Accumulator" Invariant
    // =========================================================================

    /**
     * Validates that accumulator updates are performed only when the ledger
     * records a successful transaction, thereby preventing ghost spending.
     * <p>
     * The test creates a {@link VirtualCard} and a {@link CardTransactionRequest},
     * mocks {@link AttributeEncryptor} to return a deterministic PAN fingerprint,
     * and configures the repository to return the card. When {@code ledgerSucceeds}
     * is {@code true}, the happy‑path executes {@link com.opayque.api.transactions.service.TransferService#transferFunds(UUID, String, String, String, String)}
     * When {@code ledgerSucceeds} is {@code false}, the mocked ledger service throws an
     * {@link InsufficientFundsException}, and the test asserts that
     * {@link CardLimitService#recordSpend(UUID, BigDecimal)} is never invoked, confirming
     * that accumulator state is not altered on ledger failure.
     * <p>
     * This behavior enforces the business rule that accumulator updates must mirror
     * successful ledger entries, ensuring consistency between spend limits and
     * actual funds.
     * <p>
     *
     * @param card          the {@link VirtualCard} instance generated by the {@code "validActiveCard"} provider; must be active
     * @param request       the {@link CardTransactionRequest} generated by the {@code "matchingRequest"} provider; must correspond to the card
     * @param ledgerSucceeds a {@code boolean} flag indicating whether the ledger mock should succeed ({@code true}) or simulate a failure ({@code false})
     *
     * @see CardIssuanceService
     * @see LedgerService
     * @see CardLimitService
     * @see VirtualCardRepository
     */
    @Property
    @Label("6. Accumulator updates MUST mirror Ledger success (No Ghost Spending)")
    void accumulatorUpdatesMustMirrorLedgerSuccess(
            @ForAll("validActiveCard") VirtualCard card,
            @ForAll("matchingRequest") CardTransactionRequest request,
            @ForAll boolean ledgerSucceeds
    ) {
        TestContext ctx = setup();

        try (MockedStatic<AttributeEncryptor> staticMock = mockStatic(AttributeEncryptor.class)) {
            staticMock.when(() -> AttributeEncryptor.blindIndex(request.getPan())).thenReturn("hashed_pan");
            when(ctx.repo.findByPanFingerprint("hashed_pan")).thenReturn(Optional.of(card));
            when(ctx.encryptor.convertToEntityAttribute(card.getCvv())).thenReturn(request.getCvv());
            when(ctx.encryptor.convertToEntityAttribute(card.getExpiryDate())).thenReturn(request.getExpiryDate());

            if (ledgerSucceeds) {
                // Happy Path
                ctx.service.processTransaction(request);
            } else {
                // Sad Path (Insufficient Funds)
                doThrow(new InsufficientFundsException("Fail")).when(ctx.ledgerService).recordEntry(any());

                assertThatThrownBy(() -> ctx.service.processTransaction(request))
                        .isInstanceOf(InsufficientFundsException.class);

                // Assert: NEVER record spend if ledger failed
                verify(ctx.limitService, never()).recordSpend(any(), any());
            }
        }
    }

    // =========================================================================
    // PROPERTY 7: The "Deterministic Link" Invariant
    // =========================================================================

    /**
     * Verifies that the {@code referenceId} recorded in a ledger entry is
     * deterministically derived from the {@link CardTransactionRequest#getExternalTransactionId()}
     * and therefore remains stable for identical external transaction identifiers.
     * <p>
     * The test configures a mocked {@link AttributeEncryptor} to return a fixed
     * pan fingerprint (e.g. {@code "hashed_pan"}), resolves the fingerprint to the
     * provided {@link VirtualCard}, and supplies the decrypted CVV and expiry
     * values. After invoking {@link CardTransactionService#processTransaction(CardTransactionRequest)},
     * the {@link CreateLedgerEntryRequest} captured from the {@link LedgerService}
     * is examined. The expected {@code referenceId} is computed with
     * {@link UUID#nameUUIDFromBytes(byte[])} using the UTF‑8 bytes of the
     * external transaction ID, and the test asserts equality with the actual value.
     * <p>
     * This guarantee allows downstream components to reliably correlate ledger
     * entries to external transactions without reliance on mutable or volatile data.
     *
     * @param card    the {@link VirtualCard} that is active and matches the pan fingerprint
     * @param request the {@link CardTransactionRequest} containing the external transaction
     *                identifier and other payment details required for processing
     *
     * @see TransferController
     * @see CardTransactionService
     */
    @Property
    @Label("7. External Transaction ID MUST deterministically derive Reference UUID")
    void referenceIdMustBeStableForSameExternalId(
            @ForAll("validActiveCard") VirtualCard card,
            @ForAll("matchingRequest") CardTransactionRequest request
    ) {
        TestContext ctx = setup();

        try (MockedStatic<AttributeEncryptor> staticMock = mockStatic(AttributeEncryptor.class)) {
            staticMock.when(() -> AttributeEncryptor.blindIndex(request.getPan())).thenReturn("hashed_pan");
            when(ctx.repo.findByPanFingerprint("hashed_pan")).thenReturn(Optional.of(card));
            when(ctx.encryptor.convertToEntityAttribute(card.getCvv())).thenReturn(request.getCvv());
            when(ctx.encryptor.convertToEntityAttribute(card.getExpiryDate())).thenReturn(request.getExpiryDate());

            ctx.service.processTransaction(request);

            ArgumentCaptor<CreateLedgerEntryRequest> captor = ArgumentCaptor.forClass(CreateLedgerEntryRequest.class);
            verify(ctx.ledgerService).recordEntry(captor.capture());

            // Verify Determinism
            UUID expected = UUID.nameUUIDFromBytes(request.getExternalTransactionId().getBytes(StandardCharsets.UTF_8));
            assertThat(captor.getValue().referenceId()).isEqualTo(expected);
        }
    }

    // =========================================================================
    // ARBITRARIES (Data Generators)
    // =========================================================================

    @Provide
    Arbitrary<String> validPan() {
        // Enforce oPayque BIN prefix 171103
        return Arbitraries.strings().withCharRange('0', '9').ofLength(10)
                .map(suffix -> "171103" + suffix);
    }


    @Provide
    Arbitrary<VirtualCard> validActiveCard() {
        // FIX: Use randomValue with the Builder instead of defaultFor
        // This avoids reflection failures on complex JPA entities with relationships.
        return Arbitraries.randomValue(random ->
                VirtualCard.builder()
                        .id(UUID.randomUUID())
                        .account(Account.builder().id(UUID.randomUUID()).build())
                        .status(CardStatus.ACTIVE)
                        .cvv("enc_cvv")
                        .expiryDate("enc_exp")
                        .monthlyLimit(new BigDecimal("1000.00"))
                        .build()
        );
    }

    @Provide
    Arbitrary<VirtualCard> nonActiveCard() {
        // FIX: Inherit the robust builder from validActiveCard and randomize the Status
        return validActiveCard().map(vc -> {
            // Randomly pick between non-active states for chaos coverage
            vc.setStatus(new java.util.Random().nextBoolean() ? CardStatus.FROZEN : CardStatus.TERMINATED);
            return vc;
        });
    }

    @Provide
    Arbitrary<CardTransactionRequest> validRequest() {
        return Combinators.combine(
                validPan(),
                Arbitraries.strings().withCharRange('0', '9').ofLength(3), // CVV
                Arbitraries.strings().withChars("0123456789/").ofLength(5), // Expiry
                Arbitraries.bigDecimals().between(BigDecimal.ONE, BigDecimal.TEN),
                Arbitraries.strings().alpha().ofLength(3), // Currency
                Arbitraries.strings().alpha().ofLength(10) // Merchant
        ).as((pan, cvv, exp, amt, curr, merch) ->
                CardTransactionRequest.builder()
                        .pan(pan).cvv(cvv).expiryDate(exp).amount(amt)
                        .currency(curr).merchantName(merch)
                        .externalTransactionId(UUID.randomUUID().toString())
                        .build()
        );
    }

    @Provide
    Arbitrary<CardTransactionRequest> matchingRequest() {
        // Generates a request that implicitly matches the "decrypted" mocks used in Happy Path
        return validRequest().map(req -> {
            req.setCvv("123");
            req.setExpiryDate("12/30");
            return req;
        });
    }
}