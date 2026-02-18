package com.opayque.api.card.service;

import com.opayque.api.card.controller.CardController;
import com.opayque.api.card.dto.CardTransactionRequest;
import com.opayque.api.card.dto.CardTransactionResponse;
import com.opayque.api.card.entity.CardStatus;
import com.opayque.api.card.entity.PaymentStatus;
import com.opayque.api.card.entity.VirtualCard;
import com.opayque.api.card.repository.VirtualCardRepository;
import com.opayque.api.infrastructure.encryption.AttributeEncryptor;
import com.opayque.api.infrastructure.exception.CardLimitExceededException;
import com.opayque.api.infrastructure.exception.IdempotencyException;
import com.opayque.api.infrastructure.exception.InsufficientFundsException;
import com.opayque.api.infrastructure.exception.RateLimitExceededException;
import com.opayque.api.infrastructure.idempotency.IdempotencyService;
import com.opayque.api.infrastructure.ratelimit.RateLimiterService;
import com.opayque.api.wallet.dto.CreateLedgerEntryRequest;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.AccountStatus;
import com.opayque.api.wallet.service.LedgerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * <p>
 * Integration test suite for {@link CardTransactionService}. This class validates the complete
 * processing pipeline of a card transaction, ensuring that each gate (idempotency, lookup,
 * rate‑limiting, authentication, authorization, financial limits, and execution) behaves as
 * expected under both happy‑path and failure scenarios.
 * </p>
 *
 * <p>
 * The tests orchestrate a realistic interaction between several domain services:
 * <ul>
 *   <li>{@link VirtualCardRepository} – provides lookup of virtual cards via blind index.</li>
 *   <li>{@link CardLimitService} – enforces monthly spending limits stored in Redis.</li>
 *   <li>{@link LedgerService} – debits the underlying account and records the transaction.</li>
 *   <li>{@link AttributeEncryptor} – handles encryption/decryption of sensitive card attributes.</li>
 *   <li>{@link RateLimiterService} – protects against excessive request rates.</li>
 *   <li>{@link IdempotencyService} – guarantees exactly‑once processing for repeat requests.</li>
 * </ul>
 * By mocking these collaborators, the suite isolates {@link CardTransactionService} and verifies
 * that it reacts correctly to each possible outcome, including proper compensation (e.g., rolling
 * back Redis spend when the ledger fails) and correct propagation of domain‑specific exceptions.
 * </p>
 *
 * <p>
 * The test data includes realistic card details such as {@code VALID_PAN}, {@code HASHED_PAN},
 * {@code ENCRYPTED_CVV}, {@code ENCRYPTED_EXPIRY}, and their corresponding valid values.
 * These constants enable verification of encryption handling and formatting logic for transaction
 * descriptions and reference identifiers.
 * </p>
 *
 * @author Madavan Babu
 * @since 2026
 *
 * @see CardController
 * @see VirtualCardRepository
 * @see CardTransactionService
 */
@ExtendWith(MockitoExtension.class)
class CardTransactionServiceTest {

    @Mock private VirtualCardRepository virtualCardRepository;
    @Mock private CardLimitService cardLimitService;
    @Mock private LedgerService ledgerService;
    @Mock private AttributeEncryptor attributeEncryptor;
    @Mock private RateLimiterService rateLimiterService;
    @Mock private IdempotencyService idempotencyService;

    @InjectMocks
    private CardTransactionService cardTransactionService;

    private MockedStatic<AttributeEncryptor> mockedStaticEncryptor;
    private CardTransactionRequest request;
    private VirtualCard mockCard;
    private Account mockAccount;

    private static final String VALID_PAN = "1711031111111111"; // Real oPayque BIN
    private static final String HASHED_PAN = "hashed_pan_value";
    private static final String ENCRYPTED_CVV = "enc_cvv";
    private static final String ENCRYPTED_EXPIRY = "enc_expiry";
    private static final String VALID_CVV = "123";
    private static final String VALID_EXPIRY = "12/29";

    /**
     * Sets up the common test fixtures for {@link CardTransactionServiceTest}.
     *
     * <p>Creates a {@link MockedStatic} mock for {@link AttributeEncryptor} and configures
     * {@code AttributeEncryptor.blindIndex(VALID_PAN)} to return {@code HASHED_PAN}. This deterministic
     * behaviour isolates the encryption logic from the transaction processing tests.
     *
     * <p>Instantiates a {@link CardTransactionRequest} populated with valid PAN, CVV, expiry, amount,
     * currency, merchant description, merchant code, and transaction identifier. The request object
     * is reused by all test scenarios to focus on the service logic rather than request construction.
     *
     * <p>Builds in‑memory {@link Account} and {@link VirtualCard} entities:
     * <ul>
     *   <li>The {@link VirtualCard} references the {@link Account} to model card ownership.</li>
     *   <li>It is set to {@link CardStatus#ACTIVE}, given a monthly limit of {@code 1000.00},
     *       and supplied with encrypted CVV and expiry values.</li>
     * </ul>
     *
     * @see CardTransactionService
     * @see CardController
     * @see VirtualCardRepository
     */
    @BeforeEach
    void setUp() {
        // Mock Static Method for Blind Index
        mockedStaticEncryptor = mockStatic(AttributeEncryptor.class);
        mockedStaticEncryptor.when(() -> AttributeEncryptor.blindIndex(VALID_PAN))
                .thenReturn(HASHED_PAN);

        // Setup common Request
        request = new CardTransactionRequest(
                VALID_PAN, VALID_CVV, VALID_EXPIRY,
                new BigDecimal("10.00"), "EUR", "Uber Rides", "4121", "txn_123"
        );

        // Setup common Entities
        mockAccount = Account.builder().id(UUID.randomUUID()).build();
        mockCard = VirtualCard.builder()
                .id(UUID.randomUUID())
                .account(mockAccount)
                .status(CardStatus.ACTIVE)
                .monthlyLimit(new BigDecimal("1000.00"))
                .cvv(ENCRYPTED_CVV)
                .expiryDate(ENCRYPTED_EXPIRY)
                .build();
    }

    @AfterEach
    void tearDown() {
        mockedStaticEncryptor.close();
    }

    // =========================================================================
    // TEST 1: The Golden Path
    // =========================================================================

    /**
     * Validates the full successful path of {@link CardTransactionService#processTransaction(CardTransactionRequest)}.
     *
     * <p>This test verifies that when all pre-authorisation checks pass (idempotency, rate limit,
     * card limits, CVV/Expiry validation, card status) the transaction is approved and the ledger
     * is correctly debited.
     *
     * <p>Expectations:
     * <ul>
     *   <li>Transaction status is {@code APPROED} and a transaction ID is returned.</li>
     *   <li>Services are invoked in the correct order: {@link IdempotencyService},
     *       {@link RateLimiterService}, {@link CardLimitService}, {@link LedgerService}.</li>
     *   <li>The ledger request captures the exact amount of the transaction.</li>
     *   <li>Idempotency is marked as completed to prevent duplicate processing.</li>
     * </ul>
     *
     * <p>Side effects:
     * <ul>
     *   <li>The ledger records a new entry tracking the debit.</li>
     *   <br>
     *   <li>Idempotency marker is set for the transaction ID.</li>
     *   <br>
     *   <li>Card limit service no longer updates the accumulator table (behaviour removed from service).</li>
     * </ul>
     *
     * @see CardTransactionService
     */
    @Test
    @DisplayName("1. Happy Path: Should approve transaction when all checks pass")
    void processTransaction_WhenAllChecksPass_ShouldApproveAndDebitLedger() {
        // Arrange
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));
        when(attributeEncryptor.convertToEntityAttribute(ENCRYPTED_CVV)).thenReturn(VALID_CVV);
        when(attributeEncryptor.convertToEntityAttribute(ENCRYPTED_EXPIRY)).thenReturn(VALID_EXPIRY);

        // Act
        CardTransactionResponse response = cardTransactionService.processTransaction(request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(response.getTransactionId()).isNotNull();

        // Verify Order of Operations
        verify(idempotencyService).check("txn:txn_123");
        verify(rateLimiterService).checkLimit(mockCard.getId().toString(), "card_transaction", 20);
        verify(cardLimitService).checkSpendLimit(eq(mockCard.getId()), any(), eq(new BigDecimal("10.00")));

        // Verify Ledger Call
        ArgumentCaptor<CreateLedgerEntryRequest> ledgerCaptor = ArgumentCaptor.forClass(CreateLedgerEntryRequest.class);
        verify(ledgerService).recordEntry(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().amount()).isEqualTo(new BigDecimal("10.00"));

        // Verify Accumulator Update
        // New CardLimitService does not call this anymore

        // Verify Idempotency Complete
        verify(idempotencyService).complete(eq("txn:txn_123"), anyString());
    }

    // =========================================================================
    // TEST 2: Gate 1 - Idempotency
    // =========================================================================

    /**
     * Verifies that the transaction pipeline fails fast when the idempotency check detects a duplicate.
     *
     * <p>This test simulates an {@link IdempotencyException} thrown by {@link IdempotencyService}
     * and asserts that the exception is immediately propagated without any further processing.
     *
     * <p>Expected behaviour:
     * <ul>
     *   <li>{@link IdempotencyException} is re-thrown to the caller.</li>
     *   <li>No repository or downstream service methods are invoked.</li>
     * </ul>
     *
     * @see IdempotencyService
     * @see IdempotencyException
     */
    @Test
    @DisplayName("2. Gate 1: Should fail fast if Idempotency check fails")
    void processTransaction_WhenIdempotencyFails_ShouldRethrowImmediately() {
        // Arrange
        doThrow(new IdempotencyException("Duplicate")).when(idempotencyService).check(anyString());

        // Act & Assert
        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(IdempotencyException.class);

        // Verify we never touched the DB
        verify(virtualCardRepository, never()).findByPanFingerprint(anyString());
    }

    // =========================================================================
    // TEST 3: Gate 2 - Identification (Lookup)
    // =========================================================================

    /**
     * Verifies that the transaction pipeline fails fast when no card is found by the blind index.
     *
     * <p>This test simulates a {@link VirtualCardRepository} returning {@link Optional#empty()} for the
     * hashed PAN and asserts that {@link CardTransactionService#processTransaction(CardTransactionRequest)}
     * immediately throws {@link BadCredentialsException} without any further processing.
     *
     * <p>Expected behaviour:
     * <ul>
     *   <li>{@link BadCredentialsException} is thrown with message "Invalid PAN".</li>
     *   <li>Rate limiter is never consulted.</li>
     *   <li>Ledger service is never consulted.</li>
     * </ul>
     *
     * @see CardTransactionService
     * @see VirtualCardRepository
     * @see BadCredentialsException
     *
     * @apiNote Gate 2 of the security checkpoint sequence.
     */
    @Test
    @DisplayName("3. Gate 2: Should throw BadCredentials if card not found via Blind Index")
    void processTransaction_WhenCardNotFound_ShouldThrowBadCredentials() {
        // Arrange
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid PAN");

        verify(rateLimiterService, never()).checkLimit(any(), any(), anyLong());
    }

    // =========================================================================
    // TEST 4: Gate 3 - Rate Limiting
    // =========================================================================

    /**
     * Ensures that the transaction pipeline fails fast when the rate limiter detects excessive requests.
     *
     * <p>This test configures {@link VirtualCardRepository} to return a valid {@link VirtualCard} and
     * then instructs {@link RateLimiterService} to throw {@link RateLimitExceededException}.  The
     * assertion verifies that the exception is propagated immediately, preventing any downstream
     * security or business checks from executing.
     *
     * @see RateLimiterService
     * @see RateLimitExceededException
     * @see CardTransactionService
     */
    @Test
    @DisplayName("4. Gate 3: Should propagate RateLimitExceededException")
    void processTransaction_WhenRateLimitExceeded_ShouldPropagateException() {
        // Arrange
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));
        doThrow(new RateLimitExceededException("Too many requests"))
                .when(rateLimiterService).checkLimit(any(), any(), anyLong());

        // Act & Assert
        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(RateLimitExceededException.class);
    }

    // =========================================================================
    // TEST 5: Gate 4 - Authentication (CVV)
    // =========================================================================

    /**
     * <p>Verifies that {@link CardTransactionService} rejects a transaction when the incoming CVV does not match the encrypted value stored in the database.</p>
     *
     * <p>This test simulates a Gate 4a (CVV validation) failure by:
     * <ul>
     *   <li>Retrieving a {@link VirtualCard} via its hashed PAN
     *   <item>Decrypting the stored CVV to a value different from the one sent in the request
     *   <item>Asserting that the service throws {@link BadCredentialsException} with the message "Invalid CVV"
     * </ul></p>
     *
     * <p>The purpose is to ensure that any tampering or misentry of the security code at the client side is immediately detected and rejected without proceeding to subsequent gates.</p>
     *
     * @see CardTransactionService
     * @see VirtualCard
     * @see VirtualCardRepository
     */
    @Test
    @DisplayName("5. Gate 4a: Should throw BadCredentials on CVV Mismatch")
    void processTransaction_WhenCvvMismatch_ShouldThrowBadCredentials() {
        // Arrange
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));
        when(attributeEncryptor.convertToEntityAttribute(ENCRYPTED_CVV)).thenReturn("999"); // Wrong CVV

        // Act & Assert
        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid CVV");
    }

    // =========================================================================
    // TEST 6: Gate 4 - Authentication (Expiry)
    // =========================================================================

    /**
     * Verifies that {@link CardTransactionService#processTransaction} rejects a request
     * when the presented expiry date does not match the encrypted value stored on the
     * {@linkplain VirtualCardRepository#findByPanFingerprint(String)}  virtual card.
     *
     * <p>The test encrypts an intentionally mismatched expiry ({@code "01/25"}) and
     * expects a {@link BadCredentialsException} with the message {@code "Invalid Expiry Date"}.
     *
     * @see CardTransactionService
     * @see VirtualCardRepository
     */
    @Test
    @DisplayName("6. Gate 4b: Should throw BadCredentials on Expiry Mismatch")
    void processTransaction_WhenExpiryMismatch_ShouldThrowBadCredentials() {
        // Arrange
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));
        when(attributeEncryptor.convertToEntityAttribute(ENCRYPTED_CVV)).thenReturn(VALID_CVV);
        when(attributeEncryptor.convertToEntityAttribute(ENCRYPTED_EXPIRY)).thenReturn("01/25"); // Wrong Date

        // Act & Assert
        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid Expiry Date");
    }

    // =========================================================================
    // TEST 7: Gate 5 - Authorization (Frozen)
    // =========================================================================

    /**
     * Validates Gate 5a of the 7-Gate Security Model: a frozen card must immediately abort any transaction attempt.
     * <p>
     * This test ensures that when a {@link VirtualCard} exists but its {@code status} is {@code FROZEN}, the service
     * {@link CardTransactionService#processTransaction(CardTransactionRequest)} rejects the request with an
     * {@link AccessDeniedException}. The test verifies the repository and encryption layers are consulted before the
     * status check, confirming that the freeze decision is made as early as possible within the security chain.
     *
     * <p>Sequence:
     * <ul>
     *   <li>Repository returns the card with {@code FROEN} status
     *   <li>AttributeEncryptor decrypts sensitive fields
     *   <li>Service detects frozen status and throws AccessDeniedException
     * </ul>
     *
     * @see CardTransactionService
     */
    @Test
    @DisplayName("7. Gate 5a: Should throw AccessDenied if card is FROZEN")
    void processTransaction_WhenCardFrozen_ShouldThrowAccessDenied() {
        // Arrange
        mockCard.setStatus(CardStatus.FROZEN);
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));
        when(attributeEncryptor.convertToEntityAttribute(ENCRYPTED_CVV)).thenReturn(VALID_CVV);
        when(attributeEncryptor.convertToEntityAttribute(ENCRYPTED_EXPIRY)).thenReturn(VALID_EXPIRY);

        // Act & Assert
        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("FROZEN");
    }

    // =========================================================================
    // TEST 8: Gate 5 - Authorization (Terminated)
    // =========================================================================

    /**
     * Verifies that {@link CardTransactionService#processTransaction} rejects a transaction
     * when the card’s status is {@code TERMINATED}.
     *
     * <p>This test ensures the gate-5b security rule is enforced: any attempt to use a terminated
     * card immediately raises {@link AccessDeniedException} with an explanatory message.
     *
     * @see CardTransactionService
     * @see AccessDeniedException
     */
    @Test
    @DisplayName("8. Gate 5b: Should throw AccessDenied if card is TERMINATED")
    void processTransaction_WhenCardTerminated_ShouldThrowAccessDenied() {
        // Arrange
        mockCard.setStatus(CardStatus.TERMINATED);
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));
        when(attributeEncryptor.convertToEntityAttribute(ENCRYPTED_CVV)).thenReturn(VALID_CVV);
        when(attributeEncryptor.convertToEntityAttribute(ENCRYPTED_EXPIRY)).thenReturn(VALID_EXPIRY);

        // Act & Assert
        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("TERMINATED");
    }

    // =========================================================================
    // TEST 9: Gate 6 - Financial Limits (Redis)
    // =========================================================================

    /**
     * Validates that {@link CardTransactionService#processTransaction} propagates
     * {@link CardLimitExceededException} when the monthly spend limit is exceeded.
     *
     * <p>This test ensures the gate-6 safety check is enforced: once the aggregated
     * spend for the current billing cycle crosses the threshold defined for the
     * {@link VirtualCard}, the transaction is rejected before any ledger entry is
     * created.
     *
     * <p>Expectations:
     * <ul>
     *   <li>{@link CardLimitService#checkSpendLimit} throws
     *       {@link CardLimitExceededException}.</li>
     *   <li>The exception is bubbled up to the caller without wrapping.</li>
     *   <li>{@link LedgerService#recordEntry} is never invoked.</li>
     * </ul>
     *
     * @see CardTransactionService
     * @see CardLimitService
     * @see LedgerService
     */
    @Test
    @DisplayName("9. Gate 6: Should propagate CardLimitExceededException")
    void processTransaction_WhenMonthlyLimitExceeded_ShouldPropagateException() {
        // Arrange
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));
        when(attributeEncryptor.convertToEntityAttribute(ENCRYPTED_CVV)).thenReturn(VALID_CVV);
        when(attributeEncryptor.convertToEntityAttribute(ENCRYPTED_EXPIRY)).thenReturn(VALID_EXPIRY);

        doThrow(new CardLimitExceededException("Limit hit"))
                .when(cardLimitService).checkSpendLimit(any(), any(), any());

        // Act & Assert
        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(CardLimitExceededException.class);

        // Ledger must not be called
        verify(ledgerService, never()).recordEntry(any());
    }

    // =========================================================================
    // TEST 10: Gate 7 - Execution (Insufficient Funds)
    // =========================================================================

    /**
     * <p>Verifies that {@link CardTransactionService#processTransaction} propagates
     * {@link InsufficientFundsException} when the ledger rejects the entry and,
     * crucially, does <strong>not</strong> record the spend in the downstream limit store.</p>
     *
     * <p>This test acts as Gate&nbsp;7 in the transaction validation pipeline, ensuring
     * that a failed monetary check leaves no side-effects in Redis.  It also confirms
     * that decryption of sensitive card data (CVV and expiry) is performed before the
     * ledger call, maintaining the correct temporal order of security operations.</p>
     *
     * @see LedgerService
     * @see CardLimitService
     */
    @Test
    @DisplayName("10. Gate 7: Should propagate InsufficientFundsException and NOT update accumulator")
    void processTransaction_WhenInsufficientFunds_ShouldPropagateException() {
        // Arrange
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));
        when(attributeEncryptor.convertToEntityAttribute(ENCRYPTED_CVV)).thenReturn(VALID_CVV);
        when(attributeEncryptor.convertToEntityAttribute(ENCRYPTED_EXPIRY)).thenReturn(VALID_EXPIRY);

        doThrow(new InsufficientFundsException("No money"))
                .when(ledgerService).recordEntry(any());

        // Act & Assert
        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(InsufficientFundsException.class);

        // Crucial: We must NOT record the spend in Redis if the transaction failed
        verify(cardLimitService, never()).recordSpend(any(), any());
    }

    // =========================================================================
    // TEST 11: Gate 7 - Unexpected Failure
    // =========================================================================

    /**
     * Verifies that when the ledger service throws an unexpected {@link RuntimeException}
     * (e.g., database connection lost), the transaction processing logic logs the failure
     * and re-throws the exception without marking the idempotency key as complete.
     * <p>
     * This behavior ensures that the caller can detect the failure and the operation remains
     * eligible for automatic retry or manual intervention, preserving exactly-once semantics
     * enforced by {@link IdempotencyService}.
     *
     * @see LedgerService
     * @see IdempotencyService
     * @see CardTransactionService
     */
    @Test
    @DisplayName("11. Gate 7b: Should not complete Idempotency on Unexpected Error")
    void processTransaction_WhenLedgerFailsUnexpectedly_ShouldLogAndRethrow() {
        // Arrange
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));
        when(attributeEncryptor.convertToEntityAttribute(ENCRYPTED_CVV)).thenReturn(VALID_CVV);
        when(attributeEncryptor.convertToEntityAttribute(ENCRYPTED_EXPIRY)).thenReturn(VALID_EXPIRY);

        doThrow(new RuntimeException("DB Connection Lost"))
                .when(ledgerService).recordEntry(any());

        // Act & Assert
        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(RuntimeException.class);

        // Verify Idempotency is NOT marked as complete (so it can be retried or expired)
        verify(idempotencyService, never()).complete(anyString(), anyString());
    }

    // =========================================================================
    // TEST 12: Data Integrity & Formatting
    // =========================================================================

    /**
     * Verifies that {@link CardTransactionService#processTransaction} formats the ledger
     * description using the merchant name and MCC, and derives the reference ID
     * deterministically from the transaction identifier.
     *
     * <p>This test ensures:
     * <ul>
     *   <li>The description follows the pattern {@code "POS: <merchant> | MCC: <code>"}</li>
     *   <li>The reference ID is a type-3 UUID generated from the transaction ID bytes,
     *       guaranteeing repeatability across replays</li>
     * </ul>
     *
     * @see CardTransactionService
     * @see LedgerService
     */
    @Test
    @DisplayName("12. Integrity: Should format Description and derive ReferenceID correctly")
    void processTransaction_ShouldFormatDescriptionAndDeriveReferenceIdCorrectly() {
        // Arrange
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));
        when(attributeEncryptor.convertToEntityAttribute(ENCRYPTED_CVV)).thenReturn(VALID_CVV);
        when(attributeEncryptor.convertToEntityAttribute(ENCRYPTED_EXPIRY)).thenReturn(VALID_EXPIRY);

        // Act
        cardTransactionService.processTransaction(request);

        // Assert
        ArgumentCaptor<CreateLedgerEntryRequest> captor = ArgumentCaptor.forClass(CreateLedgerEntryRequest.class);
        verify(ledgerService).recordEntry(captor.capture());

        CreateLedgerEntryRequest ledgerReq = captor.getValue();

        // Check Description Format
        assertThat(ledgerReq.description()).isEqualTo("POS: Uber Rides | MCC: 4121");

        // Check Deterministic Reference ID Generation
        UUID expectedUuid = UUID.nameUUIDFromBytes("txn_123".getBytes(StandardCharsets.UTF_8));
        assertThat(ledgerReq.referenceId()).isEqualTo(expectedUuid);
    }

    /**
     * Verifies that the distributed transaction compensator rolls back the Redis spend limit
     * when the ledger service fails to persist the accounting entry.
     *
     * <p>This test simulates a partial failure scenario: the card limit is successfully
     * reserved in Redis but the downstream {@link LedgerService} throws a runtime exception.
     * The service must detect the {@code limitReserved} flag and invoke
     * {@link CardLimitService#rollbackSpend} to release the previously reserved amount,
     * ensuring that the customer’s available balance is not incorrectly reduced.
     *
     * <p>The test validates the exact compensation path by stubbing
     * {@link LedgerService#recordEntry} to throw and then asserting that
     * {@code cardLimitService.rollbackSpend} is called with the correct card identifier
     * and amount.
     *
     * @see CardTransactionService
     * @see CardLimitService
     * @see LedgerService
     */
    @Test
    @DisplayName("Unit: Should rollback Redis spend when Ledger Service fails")
    void processTransaction_ShouldRollbackLimit_WhenLedgerFails() {
        // Arrange
        // Using existing variables from your setUp
        when(virtualCardRepository.findByPanFingerprint(anyString())).thenReturn(Optional.of(mockCard));

        // Mock security validation to pass (CVV, then Expiry)
        when(attributeEncryptor.convertToEntityAttribute(any()))
                .thenReturn(VALID_CVV)
                .thenReturn(VALID_EXPIRY);

        // Step 6: Success (This sets limitReserved = true in the Service)
        doNothing().when(cardLimitService).checkSpendLimit(any(), any(), any());

        // Step 7: SABOTAGE - Throw exception here to trigger the compensation logic
        doThrow(new RuntimeException("Ledger Database Down"))
                .when(ledgerService).recordEntry(any());

        // Act & Assert
        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Ledger Database Down");

        // VERIFY: The specific branch is hit (limitReserved is true, not a LimitExceededException)
        verify(cardLimitService).rollbackSpend(eq(mockCard.getId()), any(BigDecimal.class));
    }

    /**
     * Verifies that {@link CardTransactionService#processTransaction} does <strong>not</strong>
     * trigger a rollback when the transaction fails due to an exceeded card limit.
     *
     * <p>This test ensures that {@link CardLimitExceededException} is treated as a business-level
     * rejection rather than a technical failure. Because the limit is checked <em>before</em>
     * any spend is reserved, the service must leave the limit untouched and avoid calling
     * {@link CardLimitService#rollbackSpend}.
     *
     * <p>The scenario exercises the guard clause in the transaction pipeline that distinguishes
     * between recoverable and non-recoverable error types, guaranteeing idempotent behaviour
     * for limit-tracking operations.
     *
     * @see CardTransactionService
     * @see CardLimitService
     */
    @Test
    @DisplayName("Unit: Should NOT rollback when the error IS a LimitExceededException")
    void processTransaction_ShouldNotRollback_WhenLimitAlreadyExceeded() {
        // Arrange
        when(virtualCardRepository.findByPanFingerprint(anyString())).thenReturn(Optional.of(mockCard));

        when(attributeEncryptor.convertToEntityAttribute(any()))
                .thenReturn(VALID_CVV)
                .thenReturn(VALID_EXPIRY);

        // Step 6: FAIL with the excluded exception type
        doThrow(new com.opayque.api.infrastructure.exception.CardLimitExceededException("Limit reached"))
                .when(cardLimitService).checkSpendLimit(any(), any(), any());

        // Act & Assert
        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(com.opayque.api.infrastructure.exception.CardLimitExceededException.class);

        // VERIFY: Rollback should NEVER be called because limitReserved remained false
        verify(cardLimitService, never()).rollbackSpend(any(), any());
    }

    /**
     * Verifies the transactional flow when a spend-limit reservation succeeds
     * ({@code limitReserved = true}) but the downstream ledger service throws
     * {@link CardLimitExceededException}.<p>
     *
     * This test exercises the exclusion branch in
     * {@link CardTransactionService#processTransaction(CardTransactionRequest)}
     * where the exception type matches the configured exclusion list, causing
     * the framework to bypass the automatic rollback of the reserved limit.
     *
     * <ul>
     *   <li>Mocks a valid {@link VirtualCard} with a real UUID to prevent NPE
     *       during logging.</li>
     *   <li>Stubs {@link CardLimitService#checkSpendLimit} to complete without
     *       exception, setting {@code limitReserved = true}.</li>
     *   <li>Forces {@link LedgerService#recordEntry} to throw
     *       {@code CardLimitExceededException}.</li>
     *   <li>Asserts that the same exception is propagated to the caller.</li>
     *   <li>Confirms that {@link CardLimitService#rollbackSpend} is never
     *       invoked because the exception is on the exclusion list.</li>
     * </ul>
     *
     * @see CardTransactionService
     * @see CardLimitService
     * @see LedgerService
     */
    @Test
    @DisplayName("Unit: Hit branch where limitReserved is true but Exception is LimitExceeded")
    void processTransaction_Coverage_LimitReservedTrue_ButExceptionIsLimitExceeded() {
        // Arrange
        // FIX: Use a REAL ID to avoid the NPE at line 82 (the logger)
        mockCard.setId(UUID.randomUUID());

        when(virtualCardRepository.findByPanFingerprint(anyString())).thenReturn(Optional.of(mockCard));

        // Use lenient() to prevent UnnecessaryStubbingException if the test flow changes
        lenient().when(attributeEncryptor.convertToEntityAttribute(any()))
                .thenReturn(VALID_CVV)
                .thenReturn(VALID_EXPIRY);

        // 1. Let the limit check PASS (sets limitReserved = true)
        doNothing().when(cardLimitService).checkSpendLimit(any(), any(), any());

        // 2. Force the NEXT step (Ledger) to throw the "Excluded" exception
        // This hits: if (true && true && !(true)) -> false
        doThrow(new com.opayque.api.infrastructure.exception.CardLimitExceededException("Impossible Ledger Breach"))
                .when(ledgerService).recordEntry(any());

        // Act & Assert
        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(com.opayque.api.infrastructure.exception.CardLimitExceededException.class);

        // VERIFY: Rollback is NOT called because the exception type matched the exclusion
        verify(cardLimitService, never()).rollbackSpend(any(), any());
    }

    /**
     * Validates the transaction‐processing path when {@code limitReserved} stays {@code false}.
     * <p>
     * This test exercises the guard‐clause branch that aborts processing before any spend
     * reservation is created.  By injecting an invalid CVV it triggers an early security
     * exception, ensuring that {@link CardLimitService#rollbackSpend} is never invoked and
     * proving that the system does not attempt to compensate for an unreserved limit.
     *
     * @see CardTransactionService
     * @see CardLimitService
     */
    @Test
    @DisplayName("Coverage: Hit branch where limitReserved is false")
    void processTransaction_Coverage_LimitReservedFalse() {
        // Arrange
        mockCard.setId(UUID.randomUUID());
        when(virtualCardRepository.findByPanFingerprint(anyString())).thenReturn(Optional.of(mockCard));

        // Force security check to fail BEFORE reservation happens
        when(attributeEncryptor.convertToEntityAttribute(any())).thenReturn("WRONG_CVV");

        // Act & Assert
        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);

        // VERIFY: limitReserved is false, so no rollback
        verify(cardLimitService, never()).rollbackSpend(any(), any());
    }

    // =========================================================================
    // TEST 13: EPIC 5 - Account Status Guardrails
    // =========================================================================

    /**
     * Validates that the transaction is rejected if the underlying {@link Account}
     * is {@code FROZEN}, even if the {@link VirtualCard} itself is {@code ACTIVE}.
     *
     * <p>This ensures the "Admin Kill-Switch" works instantly. The service must
     * check the account status <i>after</i> successfully identifying the card but
     * <i>before</i> attempting any ledger movement.
     *
     * @see CardTransactionService
     * @see AccountStatus#FROZEN
     */
    void processTransaction_WhenAccountFrozen_ShouldThrowAccessDenied() {
        // Arrange
        mockAccount.setStatus(AccountStatus.FROZEN);

        // 1. Mock the Card Lookup
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));

        // 2. Mock Security Gates (CVV/Expiry) to PASS
        when(attributeEncryptor.convertToEntityAttribute(ENCRYPTED_CVV)).thenReturn(VALID_CVV);
        when(attributeEncryptor.convertToEntityAttribute(ENCRYPTED_EXPIRY)).thenReturn(VALID_EXPIRY);

        // Act & Assert
        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(AccessDeniedException.class)
                // Robustness: Use 'containing' to ignore minor typos/whitespace
                .hasMessageContaining("FROZEN");

        // Verification: The Ledger was never touched
        verify(ledgerService, never()).recordEntry(any());
        // Verification: No money was reserved in Redis (limitReserved never became true)
        verify(cardLimitService, never()).recordSpend(any(), any());
    }

    /**
     * Validates that the transaction is rejected if the underlying {@link Account}
     * is {@code CLOSED}.
     *
     * <p>This scenario typically occurs when a user soft-deletes their account,
     * but a recurring subscription (Netflix/Spotify) tries to charge a card
     * that hasn't expired yet. The system must block this to prevent "Phantom Debits".
     *
     * @see CardTransactionService
     * @see AccountStatus#CLOSED
     */
    @Test
    @DisplayName("14. Gate 5d: Should throw AccessDenied if underlying Account is CLOSED")
    void processTransaction_WhenAccountClosed_ShouldThrowAccessDenied() {
        // Arrange
        mockAccount.setStatus(AccountStatus.CLOSED);

        // 1. Mock the Card Lookup
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));

        // 2. Mock Security Gates (CVV/Expiry) - Mark LENIENT
        // FIX: We use lenient() here. If the service checks AccountStatus *before* // calling the Encryptor, Mockito won't crash complaining about "Unnecessary Stubbings".
        lenient().when(attributeEncryptor.convertToEntityAttribute(ENCRYPTED_CVV)).thenReturn(VALID_CVV);
        lenient().when(attributeEncryptor.convertToEntityAttribute(ENCRYPTED_EXPIRY)).thenReturn(VALID_EXPIRY);

        // Act: We capture the exception manually to inspect it
        Throwable thrown = catchThrowable(() -> cardTransactionService.processTransaction(request));

        // Assert
        assertThat(thrown)
                .as("Expected transaction to fail because Account is CLOSED")
                .isNotNull()
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Linked account is CLOSED");

        // Verification: The Ledger was never touched
        verify(ledgerService, never()).recordEntry(any());
    }
}