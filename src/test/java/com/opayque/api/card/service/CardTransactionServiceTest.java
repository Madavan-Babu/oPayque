package com.opayque.api.card.service;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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

    @Test
    @DisplayName("Compensatory: Should rollback Redis spend when Ledger Service fails")
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

    @Test
    @DisplayName("Compensatory: Should NOT rollback when the error IS a LimitExceededException")
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

    @Test
    @DisplayName("Coverage: Hit branch where limitReserved is true but Exception is LimitExceeded")
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
}