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
            staticMock.when(() -> AttributeEncryptor.blindIndex(request.getPan())).thenReturn("hashed_pan");
            when(ctx.repo.findByPanFingerprint("hashed_pan")).thenReturn(Optional.of(card));

            // Assertion
            assertThatThrownBy(() -> ctx.service.processTransaction(request))
                    .isInstanceOf(BadCredentialsException.class);

            // CRITICAL: Ledger must NEVER be touched
            verify(ctx.ledgerService, never()).recordEntry(any());
        }
    }

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

            // Assertion
            assertThatThrownBy(() -> ctx.service.processTransaction(request))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining(card.getStatus().name());
        }
    }

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

            // Assertion: MUST fail on Credentials (Security), NOT Status (Business Rule)
            assertThatThrownBy(() -> ctx.service.processTransaction(request))
                    .isInstanceOf(BadCredentialsException.class);
        }
    }

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
        return Arbitraries.strings().withCharRange('0', '9').ofLength(10)
                .map(suffix -> "171103" + suffix);
    }

    @Provide
    Arbitrary<VirtualCard> validActiveCard() {
        return Arbitraries.randomValue(random ->
                VirtualCard.builder()
                        .id(UUID.randomUUID())
                        .account(Account.builder().id(UUID.randomUUID()).build())
                        .status(CardStatus.ACTIVE)
                        // REFACTORED: We must supply plaintext here as Hibernate handles decryption prior to Service
                        .cvv("123")
                        .expiryDate("12/30")
                        .monthlyLimit(new BigDecimal("1000.00"))
                        .build()
        );
    }

    @Provide
    Arbitrary<VirtualCard> nonActiveCard() {
        return validActiveCard().map(vc -> {
            vc.setStatus(new java.util.Random().nextBoolean() ? CardStatus.FROZEN : CardStatus.TERMINATED);
            return vc;
        });
    }

    @Provide
    Arbitrary<CardTransactionRequest> validRequest() {
        return Combinators.combine(
                validPan(),
                Arbitraries.strings().withCharRange('0', '9').ofLength(3),
                Arbitraries.strings().withChars("0123456789/").ofLength(5),
                Arbitraries.bigDecimals().between(BigDecimal.ONE, BigDecimal.TEN),
                Arbitraries.strings().alpha().ofLength(3),
                Arbitraries.strings().alpha().ofLength(10)
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
        return validRequest().map(req -> {
            req.setCvv("123");
            req.setExpiryDate("12/30");
            return req;
        });
    }
}