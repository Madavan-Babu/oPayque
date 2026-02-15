package com.opayque.api.card.service;

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