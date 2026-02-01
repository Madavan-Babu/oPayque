package com.opayque.api.wallet.service;

import com.opayque.api.integration.currency.CurrencyExchangeService;
import com.opayque.api.wallet.dto.CreateLedgerEntryRequest;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.LedgerEntry;
import com.opayque.api.wallet.entity.TransactionType;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.repository.LedgerRepository;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.BeforeTry;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// The "Math" Layer - Property Based Testing (PBT).
@SuppressWarnings({
        "NullableProblems",          // Clears all @NonNullApi mismatches
        "unchecked",                 // Clears the generic S extends LedgerEntry warnings
        "FieldCanBeLocal",           // Clears the field warnings
        "DataFlowIssue",             // Clears 'null' is returned warnings
        "Lombok"                    // If Lombok is causing noise
})
class LedgerPropertyTest {

    private LedgerService ledgerService;
    private FakeLedgerRepository fakeRepository;
    private AccountRepository accountRepository;
    private CurrencyExchangeService exchangeService;

    private final UUID accountId = UUID.randomUUID();

    private final Account eurAccount = Account.builder()
            .id(accountId)
            .currencyCode("EUR")
            .iban("DE00123456789012345678")
            .build();


    @BeforeTry
    @SuppressWarnings("unused")
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        exchangeService = mock(CurrencyExchangeService.class);
        fakeRepository = new FakeLedgerRepository(); // Fresh repo for every run

        // FIX: Mock 'findById' to return a dynamic Account for ANY requested UUID.
        // This satisfies both the fixed 'accountId' field AND the random Jqwik UUIDs.
        when(accountRepository.findByIdForUpdate(any(UUID.class))).thenAnswer(invocation -> {
            UUID requestedId = invocation.getArgument(0);
            return Optional.of(Account.builder()
                    .id(requestedId)
                    .currencyCode("EUR") // Force EUR to simplify math verification
                    .iban("DE" + requestedId.toString().substring(0, 15)) // Dummy IBAN
                    .build());
        });

        // Mock Exchange: Deterministic rates (Keep this exactly as is)
        when(exchangeService.getRate(anyString(), anyString())).thenAnswer(invocation -> {
            String from = invocation.getArgument(0);
            if (from.equals("USD")) return new BigDecimal("0.850000");
            if (from.equals("GBP")) return new BigDecimal("1.150000");
            return BigDecimal.ONE;
        });

        ledgerService = new LedgerService(fakeRepository, accountRepository, exchangeService);
    }

    // --- PROPERTY 1: BALANCE CONSISTENCY ---
    @Property(tries = 100)
    void invariant_balance_sum(
            // Link to your UUID provider
            @ForAll("validUuids") UUID accountId,

            // Generate a List of requests using the 'validEntries' provider
            @ForAll List<@From("validEntries") CreateLedgerEntryRequest> transactions
    ) {
        // Arrange
        BigDecimal expectedBalance = BigDecimal.ZERO;

        for (CreateLedgerEntryRequest request : transactions) {
            // Fix request to match the loop's account ID
            CreateLedgerEntryRequest fixedRequest = new CreateLedgerEntryRequest(
                    accountId,
                    request.amount(),
                    request.currency(), // Could be USD, GBP, or EUR
                    request.type(),
                    request.description(),
                    request.timestamp()
            );

            // Act
            ledgerService.recordEntry(fixedRequest);

            // Track Expected Balance (Replicating Service Math)
            BigDecimal effectiveAmount = fixedRequest.amount();

            // FIX: If currency doesn't match Account (EUR), apply the Mock Rate!
            if (!fixedRequest.currency().equals("EUR")) {
                BigDecimal rate = BigDecimal.ONE;
                if (fixedRequest.currency().equals("USD")) rate = new BigDecimal("0.850000");
                if (fixedRequest.currency().equals("GBP")) rate = new BigDecimal("1.150000");

                // Apply Rate & Rounding (Bankers' Rounding)
                effectiveAmount = effectiveAmount.multiply(rate).setScale(2, RoundingMode.HALF_EVEN);
            }

            if (fixedRequest.type() == TransactionType.CREDIT) {
                expectedBalance = expectedBalance.add(effectiveAmount);
            } else {
                expectedBalance = expectedBalance.subtract(effectiveAmount);
            }
        }

        // Assert
        BigDecimal actualBalance = ledgerService.calculateBalance(accountId);

        assertThat(actualBalance)
                .as("Invariant Violation: Ledger Balance mismatch")
                .isEqualByComparingTo(expectedBalance);
    }

    // --- PROPERTY 2: CONVERSION INTEGRITY ---
    @Property(tries = 500)
    void invariant_conversion_integrity(
            @ForAll("validAmounts") BigDecimal originalAmount,
            @ForAll("validCurrencies") String currency) {

        CreateLedgerEntryRequest request = new CreateLedgerEntryRequest(
                accountId, originalAmount, currency, TransactionType.CREDIT, "PBT Check", null
        );

        LedgerEntry entry = ledgerService.recordEntry(request);

        BigDecimal rate = entry.getExchangeRate();
        BigDecimal expectedStoredAmount = originalAmount.multiply(rate).setScale(2, RoundingMode.HALF_EVEN);

        assertThat(entry.getAmount()).isEqualByComparingTo(expectedStoredAmount);
    }

    // --- GENERATORS ---

    @Provide
    @SuppressWarnings("unused") // Used via string reference in @ForAll
    Arbitrary<List<CreateLedgerEntryRequest>> validTransactions() {
        return Arbitraries.forType(CreateLedgerEntryRequest.class)
                .list().ofMinSize(1).ofMaxSize(50);
    }

    @Provide
    @SuppressWarnings("unused")
    Arbitrary<BigDecimal> validAmounts() {
        return Arbitraries.bigDecimals().between(BigDecimal.ONE, new BigDecimal("1000000")).ofScale(2);
    }

    @Provide
    @SuppressWarnings("unused")
    Arbitrary<String> validCurrencies() {
        return Arbitraries.of("USD", "EUR", "GBP");
    }

    // FIX: Explicit Generator for UUIDs to satisfy Jqwik
    @Provide
    Arbitrary<UUID> validUuids() {
        return Arbitraries.randomValue(random -> UUID.randomUUID());
    }

    // FIX: This is the missing link that 'invariant_balance_sum' is looking for.
    // It combines your existing valid inputs into a single Request object.
    @Provide
    @SuppressWarnings("unused")
    Arbitrary<CreateLedgerEntryRequest> validEntries() {
        return Combinators.combine(
                validUuids(),                        // 1. UUID accountId
                validAmounts(),                      // 2. BigDecimal amount
                validCurrencies(),                   // 3. String currency
                Arbitraries.of(TransactionType.class), // 4. TransactionType type
                Arbitraries.strings().alpha().ofLength(10), // 5. String description
                Arbitraries.of((java.time.LocalDateTime) null) // 6. LocalDateTime timestamp (THE FIX: Using .of() instead of .constant())
        ).as(CreateLedgerEntryRequest::new);
    }

    // --- FAKE REPOSITORY ---
    // Zero-Warning Implementation: Returns Empty Collections/Optionals instead of null.
    static class FakeLedgerRepository implements LedgerRepository {
        private final List<LedgerEntry> store = new ArrayList<>();

        @Override
        public LedgerEntry save(LedgerEntry entity) {
            if (entity.getId() == null) entity.setId(UUID.randomUUID());
            store.add(entity);
            return entity;
        }

        public BigDecimal getBalance(UUID accountId) {
            return store.stream()
                    .map(e -> e.getTransactionType() == TransactionType.CREDIT ? e.getAmount() : e.getAmount().negate())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public List<LedgerEntry> getAllEntries() { return new ArrayList<>(store); }

        // --- Safe Stubs (Satisfies @NonNullApi) ---

        @Override public List<LedgerEntry> findAll() { return Collections.emptyList(); }
        @Override public List<LedgerEntry> findAll(Sort sort) { return Collections.emptyList(); }
        @Override public Page<LedgerEntry> findAll(Pageable pageable) { return Page.empty(); }
        @Override public List<LedgerEntry> findAllById(Iterable<UUID> uuids) { return Collections.emptyList(); }
        @Override public <S extends LedgerEntry> List<S> saveAll(Iterable<S> entities) { return Collections.emptyList(); }
        @Override public void flush() {}
        @Override public <S extends LedgerEntry> S saveAndFlush(S entity) { return entity; }
        @Override public <S extends LedgerEntry> List<S> saveAllAndFlush(Iterable<S> entities) { return Collections.emptyList(); }
        @Override public void deleteAllInBatch(Iterable<LedgerEntry> entities) {}
        @Override public void deleteAllByIdInBatch(Iterable<UUID> uuids) {}
        @Override public void deleteAllInBatch() {}
        @Override public LedgerEntry getOne(UUID uuid) { throw new UnsupportedOperationException(); }
        @Override public LedgerEntry getReferenceById(UUID uuid) { throw new UnsupportedOperationException(); }
        @Override public LedgerEntry getById(UUID uuid) { throw new UnsupportedOperationException(); }
        @Override public <S extends LedgerEntry> Optional<S> findOne(Example<S> example) { return Optional.empty(); }
        @Override public <S extends LedgerEntry> List<S> findAll(Example<S> example) { return Collections.emptyList(); }
        @Override public <S extends LedgerEntry> List<S> findAll(Example<S> example, Sort sort) { return Collections.emptyList(); }
        @Override public <S extends LedgerEntry> Page<S> findAll(Example<S> example, Pageable pageable) { return Page.empty(); }
        @Override public <S extends LedgerEntry> long count(Example<S> example) { return 0; }
        @Override public <S extends LedgerEntry> boolean exists(Example<S> example) { return false; }
        @Override public <S extends LedgerEntry, R> R findBy(Example<S> example, Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { return null; } // Exception: Function return is generic
        @Override public Optional<LedgerEntry> findById(UUID uuid) { return Optional.empty(); }
        @Override public boolean existsById(UUID uuid) { return false; }
        @Override public long count() { return 0; }
        @Override public void deleteById(UUID uuid) {}
        @Override public void delete(LedgerEntry entity) {}
        @Override public void deleteAllById(Iterable<? extends UUID> uuids) {}
        @Override public void deleteAll(Iterable<? extends LedgerEntry> entities) {}
        @Override public void deleteAll() {}
    }
}