package com.opayque.api.transactions.service;

import com.opayque.api.infrastructure.exception.InsufficientFundsException;
import com.opayque.api.identity.entity.User;
import com.opayque.api.infrastructure.idempotency.IdempotencyService;
import com.opayque.api.wallet.dto.CreateLedgerEntryRequest;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.TransactionType;
import com.opayque.api.wallet.service.AccountService;
import com.opayque.api.wallet.service.LedgerService;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.BeforeTry;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;


/// **Epic 3: Atomic Transaction Engine — Property-Based Formal Verification**.
///
/// Unlike standard unit tests that validate discrete examples, this suite utilizes
/// **jqwik** to prove "Financial Invariants" across thousands of randomized inputs.
/// It treats the [TransferService] as a black-box state machine, asserting that
/// specific mathematical truths hold regardless of the scale or origin of the funds.
///
/// **Verified Invariants:**
/// - **Symmetric Atomicity:** Every successful transfer must generate a perfectly
///   balanced Debit/Credit pair sharing a single `referenceId`.
/// - **Value Conservation:** System total liquidity must remain constant; the amount
///   debited must bit-for-bit match the amount credited.
/// - **Narcissism Block:** Self-transfers are architecturally rejected to prevent
///   ledger bloat and circular reference complexity.
class TransferPropertyTest {

    private AccountService accountService;
    private LedgerService ledgerService;
    private TransferService transferService;
    private IdempotencyService idempotencyService;

    /// Orchestrates a clean mock environment for every individual property "try".
    ///
    /// By using `@BeforeTry`, the service and its dependencies are re-instantiated
    /// for every randomized input set, ensuring zero state leakage and maintaining
    /// the purity of the stochastic simulation.
    @BeforeTry
    void setUp() {
        accountService = Mockito.mock(AccountService.class);
        ledgerService = Mockito.mock(LedgerService.class);
        idempotencyService = Mockito.mock(IdempotencyService.class); // <--- 2. MOCK IT
        transferService = new TransferService(accountService, ledgerService, idempotencyService);
    }

    /// **Invariant: Symmetric Atomicity & Reference Linking**.
    ///
    /// Proves that for any valid financial amount and any pair of distinct users,
    /// the resulting ledger entries are mirror images of each other.
    ///
    /// **Formal Verification Criteria:**
    /// 1. **Reference Parity:** Both records MUST share the same UUID `referenceId`.
    /// 2. **Amount Equality:** `debit.amount == credit.amount == requested.amount`.
    /// 3. **Polarity Check:** Types MUST be exactly one `DEBIT` and one `CREDIT`.
    ///
    /// **Complexity Analysis:** O(N) where N is the number of jqwik tries (default: 100).
    /// This test effectively explores 100 random regions of the 128-bit UUID and
    /// [BigDecimal] decimal space.
    @Property(tries = 100)
    void transferRequestsMustAlwaysBeSymmetric(
            @ForAll("validAmounts") BigDecimal amount,
            @ForAll("validIds") UUID senderId,
            @ForAll("validIds") UUID receiverId
    ) {
        // Constraint: Sender and Receiver must be different for this property to hold
        Assume.that(!senderId.equals(receiverId));
        String currency = "USD";
        String receiverEmail = "receiver-" + receiverId + "@opayque.com";
        String idempotencyKey = UUID.randomUUID().toString(); // <--- 4. GENERATE KEY

        // 1. Mock valid accounts (Happy Path Setup)
        mockHappyPath(senderId, receiverId, receiverEmail, amount);

        // 2. Act
        transferService.transferFunds(senderId, receiverEmail, amount.toString(), currency, idempotencyKey);

        // 3. Capture & Assert Invariants
        ArgumentCaptor<CreateLedgerEntryRequest> captor = ArgumentCaptor.forClass(CreateLedgerEntryRequest.class);
        verify(ledgerService, times(2)).recordEntry(captor.capture());

        List<CreateLedgerEntryRequest> requests = captor.getAllValues();
        CreateLedgerEntryRequest req1 = requests.get(0);
        CreateLedgerEntryRequest req2 = requests.get(1);

        // INVARIANT 1: Atomicity (Shared Reference ID)
        assertThat(req1.referenceId()).isNotNull();
        assertThat(req1.referenceId()).isEqualTo(req2.referenceId());

        // INVARIANT 2: Conservation of Value (Amounts Match)
        assertThat(req1.amount()).isEqualByComparingTo(req2.amount());
        assertThat(req1.amount()).isEqualByComparingTo(amount);

        // INVARIANT 3: Directionality (One Debit, One Credit)
        assertThat(req1.type()).isNotEqualTo(req2.type());
        assertThat(List.of(req1.type(), req2.type()))
                .containsExactlyInAnyOrder(TransactionType.DEBIT, TransactionType.CREDIT);
    }


    /// **Invariant: The "Zero/Negative Wall" Security Gate**.
    ///
    /// Proves that the [TransferService] acts as a non-permeable barrier for invalid
    /// financial values. This test asserts that for the entire set of non-positive
    /// [BigDecimal] values (0 and negatives), the engine rejects the request
    /// **before** touching the persistence layer.
    ///
    /// **Security Assertion:** Verified that zero calls are dispatched to [LedgerService].
    @Property
    void transferRequestsWithInvalidAmountsMustBeRejected(
            @ForAll("invalidAmounts") BigDecimal amount,
            @ForAll("validIds") UUID senderId,
            @ForAll("validIds") UUID receiverId
    ) {
        String currency = "USD";
        String receiverEmail = "receiver@opayque.com";
        String idempotencyKey = "invalid-amt-key";

        // Act & Assert
        // We expect an IllegalArgumentException for every single negative/zero input
        try {
            transferService.transferFunds(senderId, receiverEmail, amount.toString(), currency, idempotencyKey);
            throw new AssertionError("Should have thrown IllegalArgumentException for amount: " + amount);
        } catch (IllegalArgumentException expected) {
            // Success: The gate held.
        }

        // Critical: Verify NO ledger entries were touched (Mock interaction check)
        verify(ledgerService, Mockito.never()).recordEntry(any());
        // Verify we TRIED to lock, but NEVER completed
        verify(idempotencyService).lock(idempotencyKey);
        verify(idempotencyService, Mockito.never()).complete(anyString(), anyString());

    }

    /// **Invariant: The "Narcissism Block" (Circular Prevention)**.
    ///
    /// Asserts that a user cannot initiate a transfer to an account they already own.
    /// This prevents the creation of "Infinite Loops" in reconciliation reports and
    /// protects the ledger from redundant, zero-sum record bloat.
    @Property
    void selfTransferMustAlwaysBeRejected(
            @ForAll("validIds") UUID userId,
            @ForAll("validAmounts") BigDecimal amount
    ) {
        String email = "me@opayque.com";
        String currency = "USD";
        String idempotencyKey = "self-transfer-key";

        // Mock the scenario where Sender and Receiver resolve to the SAME User ID
        // Note: We mock the accounts to return the SAME user ID
        User me = User.builder().id(userId).email(email).build();
        Account myAccount = Account.builder().id(userId).user(me).currencyCode(currency).build();

        given(accountService.getAccountById(userId)).willReturn(myAccount);
        // Receiver resolution returns the same account
        given(accountService.getAccountsForUser(email)).willReturn(List.of(myAccount));

        // Act & Assert
        try {
            transferService.transferFunds(userId, email, amount.toString(), currency, idempotencyKey);
            throw new AssertionError("Should have rejected self-transfer");
        } catch (IllegalArgumentException expected) {
            // Success
        }

        verify(ledgerService, Mockito.never()).recordEntry(any());
    }

    // --- GENERATORS (The "Chaos" Engine) ---

    /// **Chaos Engine: Non-Positive Value Generator**.
    ///
    /// Produces a distribution of negative [BigDecimal] values and the absolute zero
    /// boundary to test the resilience of the service-layer validation gates.
    @Provide
    Arbitrary<BigDecimal> invalidAmounts() {
        // Generates negatives and zero
        return Arbitraries.oneOf(
                Arbitraries.bigDecimals().lessOrEqual(BigDecimal.ZERO),
                Arbitraries.just(BigDecimal.ZERO)
        );
    }

    /// **Chaos Engine: Non-Positive Value Generator**.
    ///
    /// Produces a distribution of negative [BigDecimal] values and the absolute zero
    /// boundary to test the resilience of the service-layer validation gates.
    @Provide
    Arbitrary<BigDecimal> validAmounts() {
        // Generates amounts from 0.01 to 1 Billion, with up to 2 decimal places
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("1000000000"))
                .ofScale(2);
    }

    /// **Chaos Engine: Entity Identity Generator**.
    ///
    /// Produces high-entropy [UUID] values to simulate a high-cardinality user base,
    /// ensuring the transfer logic is not sensitive to specific primary key patterns.
    @Provide
    Arbitrary<UUID> validIds() {
        // Fix: Use a lambda that accepts the 'random' argument, even if we ignore it.
        // UUID.randomUUID() handles its own entropy.
        return Arbitraries.randomValue(random -> UUID.randomUUID());
    }

    // --- HELPER ---

    /// **Stochastic Helper: Provisioning Virtual State**.
    ///
    /// Configures the [AccountService] and [LedgerService] mocks to simulate
    /// a valid, liquid state for the randomized identities generated by jqwik.
    /// Ensures the "Sender" always has `amount + 10` available to isolate the
    /// core transfer logic from [InsufficientFundsException] noise.
    private void mockHappyPath(UUID senderId, UUID receiverId, String receiverEmail, BigDecimal amount) {
        // Sender Setup
        User senderUser = User.builder().id(senderId).email("sender@opayque.com").build();
        Account senderAccount = Account.builder().id(senderId).user(senderUser).currencyCode("USD").build();
        given(accountService.getAccountById(senderId)).willReturn(senderAccount);
        given(ledgerService.calculateBalance(senderId)).willReturn(amount.add(BigDecimal.TEN)); // Always has enough funds

        // Receiver Setup
        User receiverUser = User.builder().id(receiverId).email(receiverEmail).build();
        Account receiverAccount = Account.builder().id(receiverId).user(receiverUser).currencyCode("USD").build();
        given(accountService.getAccountsForUser(receiverEmail)).willReturn(List.of(receiverAccount));
    }
}