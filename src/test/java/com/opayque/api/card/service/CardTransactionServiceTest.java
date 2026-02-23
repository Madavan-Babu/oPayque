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
    @Mock
    private RateLimiterService rateLimiterService;
    @Mock private IdempotencyService idempotencyService;

    @InjectMocks
    private CardTransactionService cardTransactionService;

    private MockedStatic<AttributeEncryptor> mockedStaticEncryptor;
    private CardTransactionRequest request;
    private VirtualCard mockCard;
    private Account mockAccount;

    private static final String VALID_PAN = "1711031111111111";
    private static final String HASHED_PAN = "hashed_pan_value";
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
        mockAccount = Account.builder().id(UUID.randomUUID()).status(AccountStatus.ACTIVE).build();

        // FIX: Card is now instantiated with plaintext values, as Hibernate would map it.
        mockCard = VirtualCard.builder()
                .id(UUID.randomUUID())
                .account(mockAccount)
                .status(CardStatus.ACTIVE)
                .monthlyLimit(new BigDecimal("1000.00"))
                .cvv(VALID_CVV)
                .expiryDate(VALID_EXPIRY)
                .build();
    }

    @AfterEach
    void tearDown() {
        mockedStaticEncryptor.close();
    }

    @Test
    @DisplayName("1. Happy Path: Should approve transaction when all checks pass")
    void processTransaction_WhenAllChecksPass_ShouldApproveAndDebitLedger() {
        // Arrange
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));

        // Act
        CardTransactionResponse response = cardTransactionService.processTransaction(request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(response.getTransactionId()).isNotNull();

        verify(idempotencyService).check("txn:txn_123");
        verify(rateLimiterService).checkLimit(mockCard.getId().toString(), "card_transaction", 20);
        verify(cardLimitService).checkSpendLimit(eq(mockCard.getId()), any(), eq(new BigDecimal("10.00")));

        ArgumentCaptor<CreateLedgerEntryRequest> ledgerCaptor = ArgumentCaptor.forClass(CreateLedgerEntryRequest.class);
        verify(ledgerService).recordEntry(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().amount()).isEqualTo(new BigDecimal("10.00"));

        verify(idempotencyService).complete(eq("txn:txn_123"), anyString());
    }

    @Test
    @DisplayName("2. Gate 1: Should fail fast if Idempotency check fails")
    void processTransaction_WhenIdempotencyFails_ShouldRethrowImmediately() {
        doThrow(new IdempotencyException("Duplicate")).when(idempotencyService).check(anyString());

        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(IdempotencyException.class);

        verify(virtualCardRepository, never()).findByPanFingerprint(anyString());
    }

    @Test
    @DisplayName("3. Gate 2: Should throw BadCredentials if card not found via Blind Index")
    void processTransaction_WhenCardNotFound_ShouldThrowBadCredentials() {
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid PAN");

        verify(rateLimiterService, never()).checkLimit(any(), any(), anyLong());
    }

    @Test
    @DisplayName("4. Gate 3: Should propagate RateLimitExceededException")
    void processTransaction_WhenRateLimitExceeded_ShouldPropagateException() {
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));
        doThrow(new RateLimitExceededException("Too many requests"))
                .when(rateLimiterService).checkLimit(any(), any(), anyLong());

        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("5. Gate 4a: Should throw BadCredentials on CVV Mismatch")
    void processTransaction_WhenCvvMismatch_ShouldThrowBadCredentials() {
        // Arrange: Mutate the entity to have the wrong plaintext CVV
        mockCard.setCvv("999");
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));

        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid CVV");
    }

    @Test
    @DisplayName("6. Gate 4b: Should throw BadCredentials on Expiry Mismatch")
    void processTransaction_WhenExpiryMismatch_ShouldThrowBadCredentials() {
        // Arrange: Mutate the entity to have the wrong expiry
        mockCard.setExpiryDate("01/25");
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));

        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid Expiry Date");
    }

    @Test
    @DisplayName("7. Gate 5a: Should throw AccessDenied if card is FROZEN")
    void processTransaction_WhenCardFrozen_ShouldThrowAccessDenied() {
        mockCard.setStatus(CardStatus.FROZEN);
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));

        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("FROZEN");
    }

    @Test
    @DisplayName("8. Gate 5b: Should throw AccessDenied if card is TERMINATED")
    void processTransaction_WhenCardTerminated_ShouldThrowAccessDenied() {
        mockCard.setStatus(CardStatus.TERMINATED);
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));

        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("TERMINATED");
    }

    @Test
    @DisplayName("9. Gate 6: Should propagate CardLimitExceededException")
    void processTransaction_WhenMonthlyLimitExceeded_ShouldPropagateException() {
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));

        doThrow(new CardLimitExceededException("Limit hit"))
                .when(cardLimitService).checkSpendLimit(any(), any(), any());

        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(CardLimitExceededException.class);

        verify(ledgerService, never()).recordEntry(any());
    }

    @Test
    @DisplayName("10. Gate 7: Should propagate InsufficientFundsException and NOT update accumulator")
    void processTransaction_WhenInsufficientFunds_ShouldPropagateException() {
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));

        doThrow(new InsufficientFundsException("No money"))
                .when(ledgerService).recordEntry(any());

        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(InsufficientFundsException.class);

        verify(cardLimitService, never()).recordSpend(any(), any());
    }

    @Test
    @DisplayName("11. Gate 7b: Should not complete Idempotency on Unexpected Error")
    void processTransaction_WhenLedgerFailsUnexpectedly_ShouldLogAndRethrow() {
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));

        doThrow(new RuntimeException("DB Connection Lost"))
                .when(ledgerService).recordEntry(any());

        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(RuntimeException.class);

        verify(idempotencyService, never()).complete(anyString(), anyString());
    }

    @Test
    @DisplayName("12. Integrity: Should format Description and derive ReferenceID correctly")
    void processTransaction_ShouldFormatDescriptionAndDeriveReferenceIdCorrectly() {
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));

        cardTransactionService.processTransaction(request);

        ArgumentCaptor<CreateLedgerEntryRequest> captor = ArgumentCaptor.forClass(CreateLedgerEntryRequest.class);
        verify(ledgerService).recordEntry(captor.capture());

        CreateLedgerEntryRequest ledgerReq = captor.getValue();
        assertThat(ledgerReq.description()).isEqualTo("POS: Uber Rides | MCC: 4121");

        UUID expectedUuid = UUID.nameUUIDFromBytes("txn_123".getBytes(StandardCharsets.UTF_8));
        assertThat(ledgerReq.referenceId()).isEqualTo(expectedUuid);
    }

    @Test
    @DisplayName("Unit: Should rollback Redis spend when Ledger Service fails")
    void processTransaction_ShouldRollbackLimit_WhenLedgerFails() {
        when(virtualCardRepository.findByPanFingerprint(anyString())).thenReturn(Optional.of(mockCard));

        doNothing().when(cardLimitService).checkSpendLimit(any(), any(), any());

        doThrow(new RuntimeException("Ledger Database Down"))
                .when(ledgerService).recordEntry(any());

        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Ledger Database Down");

        verify(cardLimitService).rollbackSpend(eq(mockCard.getId()), any(BigDecimal.class));
    }

    @Test
    @DisplayName("Unit: Should NOT rollback when the error IS a LimitExceededException")
    void processTransaction_ShouldNotRollback_WhenLimitAlreadyExceeded() {
        when(virtualCardRepository.findByPanFingerprint(anyString())).thenReturn(Optional.of(mockCard));

        doThrow(new com.opayque.api.infrastructure.exception.CardLimitExceededException("Limit reached"))
                .when(cardLimitService).checkSpendLimit(any(), any(), any());

        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(com.opayque.api.infrastructure.exception.CardLimitExceededException.class);

        verify(cardLimitService, never()).rollbackSpend(any(), any());
    }

    @Test
    @DisplayName("Unit: Hit branch where limitReserved is true but Exception is LimitExceeded")
    void processTransaction_Coverage_LimitReservedTrue_ButExceptionIsLimitExceeded() {
        mockCard.setId(UUID.randomUUID());
        when(virtualCardRepository.findByPanFingerprint(anyString())).thenReturn(Optional.of(mockCard));

        doNothing().when(cardLimitService).checkSpendLimit(any(), any(), any());

        doThrow(new com.opayque.api.infrastructure.exception.CardLimitExceededException("Impossible Ledger Breach"))
                .when(ledgerService).recordEntry(any());

        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(com.opayque.api.infrastructure.exception.CardLimitExceededException.class);

        verify(cardLimitService, never()).rollbackSpend(any(), any());
    }

    @Test
    @DisplayName("Coverage: Hit branch where limitReserved is false")
    void processTransaction_Coverage_LimitReservedFalse() {
        mockCard.setId(UUID.randomUUID());
        mockCard.setCvv("WRONG_CVV"); // Trigger early security failure
        when(virtualCardRepository.findByPanFingerprint(anyString())).thenReturn(Optional.of(mockCard));

        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);

        verify(cardLimitService, never()).rollbackSpend(any(), any());
    }

    @Test
    @DisplayName("13. Gate 5c: Should throw AccessDenied if underlying Account is FROZEN")
    void processTransaction_WhenAccountFrozen_ShouldThrowAccessDenied() {
        mockAccount.setStatus(AccountStatus.FROZEN);
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));

        assertThatThrownBy(() -> cardTransactionService.processTransaction(request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("FROZEN");

        verify(ledgerService, never()).recordEntry(any());
        verify(cardLimitService, never()).recordSpend(any(), any());
    }

    @Test
    @DisplayName("14. Gate 5d: Should throw AccessDenied if underlying Account is CLOSED")
    void processTransaction_WhenAccountClosed_ShouldThrowAccessDenied() {
        mockAccount.setStatus(AccountStatus.CLOSED);
        when(virtualCardRepository.findByPanFingerprint(HASHED_PAN)).thenReturn(Optional.of(mockCard));

        Throwable thrown = catchThrowable(() -> cardTransactionService.processTransaction(request));

        assertThat(thrown)
                .as("Expected transaction to fail because Account is CLOSED")
                .isNotNull()
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Linked account is CLOSED");

        verify(ledgerService, never()).recordEntry(any());
    }
}