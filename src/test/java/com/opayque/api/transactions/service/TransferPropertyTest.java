package com.opayque.api.transactions.service;

import com.opayque.api.identity.entity.User;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/// Epic 3: Atomic Transaction Engine - Property-Based Testing.
///
/// Unlike Unit Tests which check "10 + 10 = 20", these tests prove "Invariants".
/// Invariant Tested: "Atomic Consistency"
/// - For ANY random valid amount and ANY random users:
///   1. The Debit and Credit MUST share the exact same Reference ID.
///   2. The Debit Amount MUST equal the Credit Amount.
///   3. The Transaction Types MUST be opposite.
class TransferPropertyTest {

    private AccountService accountService;
    private LedgerService ledgerService;
    private TransferService transferService;

    @BeforeTry
    void setUp() {
        accountService = Mockito.mock(AccountService.class);
        ledgerService = Mockito.mock(LedgerService.class);
        transferService = new TransferService(accountService, ledgerService);
    }

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

        // 1. Mock valid accounts (Happy Path Setup)
        mockHappyPath(senderId, receiverId, receiverEmail, amount);

        // 2. Act
        transferService.transferFunds(senderId, receiverEmail, amount.toString(), currency);

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

    /// INVARIANT 2: The "Zero/Negative Wall"
    /// Proves that for ANY non-positive number (infinite set of negatives),
    /// the transfer is REJECTED and NO calls are made to the Ledger.
    @Property
    void transferRequestsWithInvalidAmountsMustBeRejected(
            @ForAll("invalidAmounts") BigDecimal amount,
            @ForAll("validIds") UUID senderId,
            @ForAll("validIds") UUID receiverId
    ) {
        String currency = "USD";
        String receiverEmail = "receiver@opayque.com";

        // Act & Assert
        // We expect an IllegalArgumentException for every single negative/zero input
        try {
            transferService.transferFunds(senderId, receiverEmail, amount.toString(), currency);
            throw new AssertionError("Should have thrown IllegalArgumentException for amount: " + amount);
        } catch (IllegalArgumentException expected) {
            // Success: The gate held.
        }

        // Critical: Verify NO ledger entries were touched (Mock interaction check)
        verify(ledgerService, Mockito.never()).recordEntry(any());
    }

    /// INVARIANT 3: The "Narcissism Block"
    /// Proves that for ANY user (random UUID), trying to send money to themselves
    /// is ALWAYS rejected, regardless of the amount.
    @Property
    void selfTransferMustAlwaysBeRejected(
            @ForAll("validIds") UUID userId,
            @ForAll("validAmounts") BigDecimal amount
    ) {
        String email = "me@opayque.com";
        String currency = "USD";

        // Mock the scenario where Sender and Receiver resolve to the SAME User ID
        // Note: We mock the accounts to return the SAME user ID
        User me = User.builder().id(userId).email(email).build();
        Account myAccount = Account.builder().id(userId).user(me).currencyCode(currency).build();

        given(accountService.getAccountById(userId)).willReturn(myAccount);
        // Receiver resolution returns the same account
        given(accountService.getAccountsForUser(email)).willReturn(List.of(myAccount));

        // Act & Assert
        try {
            transferService.transferFunds(userId, email, amount.toString(), currency);
            throw new AssertionError("Should have rejected self-transfer");
        } catch (IllegalArgumentException expected) {
            // Success
        }

        verify(ledgerService, Mockito.never()).recordEntry(any());
    }

    // --- GENERATORS (The "Chaos" Engine) ---
    @Provide
    Arbitrary<BigDecimal> invalidAmounts() {
        // Generates negatives and zero
        return Arbitraries.oneOf(
                Arbitraries.bigDecimals().lessOrEqual(BigDecimal.ZERO),
                Arbitraries.just(BigDecimal.ZERO)
        );
    }


    @Provide
    Arbitrary<BigDecimal> validAmounts() {
        // Generates amounts from 0.01 to 1 Billion, with up to 2 decimal places
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("1000000000"))
                .ofScale(2);
    }


    @Provide
    Arbitrary<UUID> validIds() {
        // Fix: Use a lambda that accepts the 'random' argument, even if we ignore it.
        // UUID.randomUUID() handles its own entropy.
        return Arbitraries.randomValue(random -> UUID.randomUUID());
    }

    // --- HELPER ---

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