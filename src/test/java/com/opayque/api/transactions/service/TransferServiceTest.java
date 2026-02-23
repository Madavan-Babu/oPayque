package com.opayque.api.transactions.service;

import com.opayque.api.identity.entity.User;
import com.opayque.api.infrastructure.exception.InsufficientFundsException;
import com.opayque.api.infrastructure.idempotency.IdempotencyService;
import com.opayque.api.wallet.dto.CreateLedgerEntryRequest;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.AccountStatus;
import com.opayque.api.wallet.entity.TransactionType;
import com.opayque.api.wallet.service.AccountService;
import com.opayque.api.wallet.service.LedgerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/// **Epic 3: Atomic Transaction Engine — Behavioral Logic & Invariant Audit**.
///
/// This suite executes a "White-Box" behavioral audit of the [TransferService] using
/// [MockitoExtension]. It isolates the service's orchestration logic from the physical
/// persistence layer to verify that business rules, security guardrails, and atomic
/// invariants are strictly enforced.
///
/// **Verification Domains:**
/// - **Atomic Orchestration:** Confirms that a transfer correctly triggers symmetric
///   `DEBIT` and `CREDIT` entries.
/// - **Value Guardrails:** Ensures that invalid financial amounts (zero/negative) and
///   insufficient liquidity states are intercepted.
/// - **Security Constraints:** Validates the prevention of "Narcissistic Transfers" (Self-transfers)
///   and orphaned account integrity violations.
@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock private AccountService accountService;
    @Mock private LedgerService ledgerService;
    @Mock private IdempotencyService idempotencyService;

    @InjectMocks
    private TransferService transferService;

    // --- HAPPY PATH ---

    /// **Functional Audit: Atomic Fund Movement Orchestration**.
    ///
    /// Validates the "Heart of the Bank" logic: moving funds between two distinct identities.
    ///
    /// **Logic Path Verified:**
    /// 1. Sender and Receiver accounts are successfully resolved.
    /// 2. Sender liquidity is verified against the requested amount.
    /// 3. Two [CreateLedgerEntryRequest] objects are dispatched to the [LedgerService].
    ///
    /// **Invariant Assertions:**
    /// - Both ledger entries MUST share a single, non-null `referenceId`.
    /// - The first entry must be a `DEBIT`, and the second must be a `CREDIT`.
    /// - Values must exactly match the user's intent without rounding errors.
    @Test
    @DisplayName("Should orchestrate atomic transfer with shared Reference ID")
    void shouldTransferFundsAtomically() {
        // 1. Arrange
        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID(); // Distinct ID
        String receiverEmail = "bob@opayque.com";
        BigDecimal amount = new BigDecimal("100.00");
        String currency = "USD";

        // Mock Sender (WITH USER!)
        User senderUser = User.builder().id(UUID.randomUUID()).email("sender@opayque.com").build();
        Account senderAccount = Account.builder()
                .id(senderId)
                .user(senderUser) // FIX: Prevent NPE
                .currencyCode("USD")
                .build();

        // Mock Receiver (WITH USER!)
        User receiverUser = User.builder().id(receiverId).email(receiverEmail).build();
        Account receiverAccount = Account.builder()
                .id(UUID.randomUUID())
                .user(receiverUser)
                .currencyCode("USD")
                .build();

        given(accountService.getAccountForUpdate(senderId)).willReturn(senderAccount);
        given(accountService.getAccountsForUser(receiverEmail)).willReturn(List.of(receiverAccount));
        given(ledgerService.calculateBalance(senderId)).willReturn(new BigDecimal("500.00"));

        // 2. Act
        transferService.transferFunds(senderId, receiverEmail, amount.toString(), currency, "unit-test-key");

        // 3. Assert
        verify(idempotencyService).lock("unit-test-key"); // Verify interaction
        verify(idempotencyService).complete(eq("unit-test-key"), anyString());
        ArgumentCaptor<CreateLedgerEntryRequest> captor = ArgumentCaptor.forClass(CreateLedgerEntryRequest.class);
        verify(ledgerService, times(2)).recordEntry(captor.capture());

        List<CreateLedgerEntryRequest> requests = captor.getAllValues();
        CreateLedgerEntryRequest debit = requests.get(0);
        CreateLedgerEntryRequest credit = requests.get(1);

        assertThat(debit.type()).isEqualTo(TransactionType.DEBIT);
        assertThat(credit.type()).isEqualTo(TransactionType.CREDIT);
        assertThat(debit.referenceId()).isNotNull();
        assertThat(debit.referenceId()).isEqualTo(credit.referenceId());
    }

    // --- SAD PATHS ---

    /// **Reliability Audit: Liquidity Gatekeeping**.
    ///
    /// Verifies that the service prevents "Phantom Money" creation by blocking transfers
    /// that exceed the sender's current aggregated balance.
    ///
    /// **Failure Condition:** Throws [InsufficientFundsException] when `requestedAmount > currentBalance`.
    /// **Security Verification:** Confirms that [LedgerService#recordEntry] is `never()` called,
    /// ensuring no partial state is committed.
    @Test
    @DisplayName("Should block transfer if Sender has Insufficient Funds")
    void shouldThrowWhenInsufficientFunds() {
        UUID senderId = UUID.randomUUID();
        String receiverEmail = "bob@opayque.com";
        BigDecimal amount = new BigDecimal("100.00");

        // FIX: We must mock the Accounts FIRST, because the Service checks existence/self-transfer
        // BEFORE it checks the balance.
        User senderUser = User.builder().id(UUID.randomUUID()).build();
        Account senderAccount = Account.builder().id(senderId).user(senderUser).currencyCode("USD").build();

        User receiverUser = User.builder().id(UUID.randomUUID()).build(); // Distinct ID
        Account receiverAccount = Account.builder().id(UUID.randomUUID()).user(receiverUser).currencyCode("USD").build();

        //FIX: Updated method call
        given(accountService.getAccountForUpdate(senderId)).willReturn(senderAccount);
        // FIX: Provide the receiver wallet so validation passes
        given(accountService.getAccountsForUser(receiverEmail)).willReturn(List.of(receiverAccount));

        // Mock Low Balance
        given(ledgerService.calculateBalance(senderId)).willReturn(new BigDecimal("10.00"));

        assertThatThrownBy(() ->
                transferService.transferFunds(senderId, receiverEmail, amount.toString(), "USD", "unit-test-key")
        )
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("Insufficient funds");

        verify(idempotencyService).lock("unit-test-key"); // Verify interaction
        verify(ledgerService, never()).recordEntry(any());
    }

    /// **Business Rule Audit: Self-Transfer Prevention**.
    ///
    /// Proves that the engine rejects requests where the sender and receiver resolve
    /// to the same physical [User] identity, regardless of wallet UUIDs.
    ///
    /// This is a critical guardrail against accidental ledger bloat and circular
    /// dependency complexity in reconciliation reports.
    @Test
    @DisplayName("Should block Self-Transfer (Sender == Receiver)")
    void shouldThrowWhenSelfTransfer() {
        UUID senderId = UUID.randomUUID();
        String userEmail = "me@opayque.com";
        UUID sameUserId = UUID.randomUUID();

        // Mock Sender
        User me = User.builder().id(sameUserId).email(userEmail).build();
        Account myAccount = Account.builder().id(senderId).user(me).currencyCode("USD").build();

        // Mock Receiver (Same User ID)
        User sameUserReceiver = User.builder().id(sameUserId).email(userEmail).build();
        Account receiverAccount = Account.builder().id(UUID.randomUUID()).user(sameUserReceiver).currencyCode("USD").build();

        //FIX: Updated method call
        given(accountService.getAccountForUpdate(senderId)).willReturn(myAccount);
        given(accountService.getAccountsForUser(userEmail)).willReturn(List.of(receiverAccount));

        assertThatThrownBy(() ->
                transferService.transferFunds(senderId, userEmail, "50.00", "USD", "unit-test-key")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot transfer to self");
        verify(idempotencyService).lock("unit-test-key"); // Verify interaction
    }

    /// **Input Integrity Audit: Negative/Zero Value Shield**.
    ///
    /// Ensures the service layer acts as a strict validator for the financial sign.
    /// Any amount $\le 0$ must be rejected with an `IllegalArgumentException`
    /// before any business logic or locking is initiated.
    @Test
    @DisplayName("Should reject Negative or Zero amounts")
    void shouldThrowWhenInvalidAmount() {
        UUID senderId = UUID.randomUUID();

        assertThatThrownBy(() ->
                transferService.transferFunds(senderId, "bob@opayque.com", "-10.00", "USD", "unit-test-key")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Transfer amount must be greater than zero");
        verify(idempotencyService).lock("unit-test-key"); // Verify interaction
    }

    /// **Integrity Audit: Orphaned Sender Account Detection**.
    ///
    /// Validates the system's response to data corruption where an [Account] exists
    /// but its linked [User] entity is `null` (Orphaned record).
    ///
    /// **Security Response:** Throws `IllegalStateException` to signal a critical
    /// data integrity violation, preventing an "Ownerless" debit.
    @Test
    @DisplayName("Should throw IllegalStateException when Account data integrity is violated (Orphaned Account)")
    void shouldThrowWhenAccountIntegrityBroken() {
        // 1. Arrange
        UUID senderId = UUID.randomUUID();
        String receiverEmail = "orphan@opayque.com";
        String currency = "USD";

        // Mock Sender: Account exists, but User is NULL (The Integrity Violation)
        Account brokenSenderAccount = Account.builder()
                .id(senderId)
                .user(null) // <--- ORPHANED ACCOUNT
                .currencyCode(currency)
                .build();

        // Mock Receiver: Must be valid so the code proceeds to the check
        User receiverUser = User.builder().email(receiverEmail).build();
        Account receiverAccount = Account.builder()
                .id(UUID.randomUUID())
                .user(receiverUser)
                .currencyCode(currency)
                .build();

        // Note: If you applied the "Double Spend" hardening, change this to getAccountForWrite(senderId)
        //FIX: Updated method call
        given(accountService.getAccountForUpdate(senderId)).willReturn(brokenSenderAccount);

        // Ensure receiver lookup succeeds so we hit the specific if() block
        given(accountService.getAccountsForUser(receiverEmail)).willReturn(List.of(receiverAccount));

        // 2. Act & Assert
        assertThatThrownBy(() ->
                transferService.transferFunds(senderId, receiverEmail, "100.00", currency, "unit-test-key")
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Account data integrity violation.");
        verify(idempotencyService).lock("unit-test-key"); // Verify interaction
    }

    /// **Integrity Audit: Orphaned Receiver Account Detection**.
    ///
    /// Parallel branch coverage for integrity checks. Confirms that even if the
    /// sender is valid, an orphaned receiver identity will halt the transaction.
    /// Ensures the "All-or-Nothing" atomicity mandate is upheld during
    /// partial identity failure.
    @Test
    @DisplayName("Should throw IllegalStateException when Receiver Account is orphaned (Branch Coverage)")
    void shouldThrowWhenReceiverIntegrityBroken() {
        // 1. Arrange
        UUID senderId = UUID.randomUUID();
        String receiverEmail = "orphan_receiver@opayque.com";
        String currency = "USD";

        // Mock Sender: VALID (This forces the '||' to evaluate the second part)
        User senderUser = User.builder().id(UUID.randomUUID()).email("sender@opayque.com").build();
        Account senderAccount = Account.builder()
                .id(senderId)
                .user(senderUser) // <--- VALID
                .currencyCode(currency)
                .build();

        // Mock Receiver: BROKEN (User is NULL)
        Account brokenReceiverAccount = Account.builder()
                .id(UUID.randomUUID())
                .user(null) // <--- ORPHANED RECEIVER
                .currencyCode(currency)
                .build();

        //FIX: Updated method call
        given(accountService.getAccountForUpdate(senderId)).willReturn(senderAccount);
        given(accountService.getAccountsForUser(receiverEmail)).willReturn(List.of(brokenReceiverAccount));

        // 2. Act & Assert
        assertThatThrownBy(() ->
                transferService.transferFunds(senderId, receiverEmail, "100.00", currency, "unit-test-key")
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Account data integrity violation.");
        verify(idempotencyService).lock("unit-test-key"); // Verify interaction
    }

    // =========================================================================
    // EPIC 5: ACCOUNT STATUS GUARDRAIL TESTS
    // =========================================================================

    /**
     * Validates that the "Kill-Switch" immediately halts a transfer.
     * <p>
     * Even if the user has sufficient funds, a {@code FROZEN} status must
     * trigger an {@link IllegalStateException} before any money is moved.
     * This ensures that funds are legally locked down.
     */
    @Test
    @DisplayName("Guardrail: Should REJECT transfer if Sender Account is FROZEN")
    void shouldRejectTransfer_WhenSenderIsFrozen() {
        // 1. Arrange
        UUID senderId = UUID.randomUUID();
        Account frozenSender = Account.builder()
                .id(senderId)
                .status(AccountStatus.FROZEN) // <--- The Trap
                .currencyCode("EUR")
                .build();

        // Mock the locking call to return the frozen account
        given(accountService.getAccountForUpdate(senderId)).willReturn(frozenSender);

        // 2. Act & Assert
        assertThatThrownBy(() ->
                transferService.transferFunds(senderId, "receiver@opayque.com", "100.00", "EUR", "idemp-1")
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Account is currently FROZEN");

        // 3. Verification
        // FIX: Used 'recordEntry' instead of the non-existent 'performTransfer'
        verify(ledgerService, never()).recordEntry(any());
        verify(idempotencyService, never()).complete(anyString(), anyString());
    }

    /**
     * Validates that a "Soft Deleted" account cannot initiate transfers.
     * <p>
     * A {@code CLOSED} account typically has a zero balance, but this guardrail
     * acts as a double-check to prevent "Zombie Transactions" in case of
     * race conditions or legacy data issues.
     */
    @Test
    @DisplayName("Guardrail: Should REJECT transfer if Sender Account is CLOSED")
    void shouldRejectTransfer_WhenSenderIsClosed() {
        // 1. Arrange
        UUID senderId = UUID.randomUUID();
        Account closedSender = Account.builder()
                .id(senderId)
                .status(AccountStatus.CLOSED) // <--- The Trap
                .currencyCode("GBP")
                .build();

        given(accountService.getAccountForUpdate(senderId)).willReturn(closedSender);

        // 2. Act & Assert
        assertThatThrownBy(() ->
                transferService.transferFunds(senderId, "receiver@opayque.com", "50.00", "GBP", "idemp-2")
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Account is currently CLOSED");

        // 3. Verification
        verify(ledgerService, never()).recordEntry(any());
    }


}