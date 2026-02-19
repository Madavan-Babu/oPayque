package com.opayque.api.statement.service;

import com.opayque.api.identity.entity.User;
import com.opayque.api.infrastructure.ratelimit.RateLimiterService;
import com.opayque.api.infrastructure.util.SecurityUtil;
import com.opayque.api.statement.dto.StatementExportRequest;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.LedgerEntry;
import com.opayque.api.wallet.entity.TransactionType;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.repository.LedgerRepository;
import jakarta.persistence.EntityManager;
import net.jqwik.api.*;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.data.domain.SliceImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Property-Based Testing (PBT) Suite for StatementService.
 * <p>
 * Evaluates core business invariants against thousands of randomized permutations
 * to mathematically prove the resilience of the CSV generation, formatting logic,
 * and security barriers.
 *
 * @author Madavan Babu
 * @since 2026
 */
class StatementServicePropertyTest {

    private final UUID accountId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    // ==================================================================================
    // 1. CSV INJECTION NEUTRALIZATION INVARIANT
    // ==================================================================================

    @Property(tries = 100)
    void propertyCsvInjectionNeutralization(
            @ForAll @StringLength(min = 1, max = 20) String payload,
            @ForAll("injectionPrefixes") String prefix) {

        String maliciousDescription = prefix + payload;
        String output = executeExportWithMockedEntry(maliciousDescription, "EUR", BigDecimal.TEN, null, null);

        // The exact malicious prefix should NEVER be at the start of the field
        assertFalse(output.contains("," + maliciousDescription + ","),
                "CSV Injection payload bypassed sanitization: " + maliciousDescription);
    }

    @Provide
    Arbitrary<String> injectionPrefixes() {
        return Arbitraries.of("=", "+", "-", "@", "==", "++", "-+", "@=");
    }

    // ==================================================================================
    // 2. IBAN MASKING CONSISTENCY INVARIANT
    // ==================================================================================

    @Property(tries = 100)
    void propertyIbanMaskingConsistency(@ForAll @StringLength(min = 0, max = 34) String randomIban) {

        LedgerRepository ledgerRepo = mock(LedgerRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        RateLimiterService rateLimiter = mock(RateLimiterService.class);
        StatementService service = createServiceWithMockEntityManager(ledgerRepo, accountRepo, rateLimiter);

        Account account = createBaseAccount(randomIban);
        when(accountRepo.findById(accountId)).thenReturn(Optional.of(account));
        when(ledgerRepo.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(any(), any(), any(), any()))
                .thenReturn(new SliceImpl<>(Collections.emptyList()));

        StringWriter sw = new StringWriter();

        try (MockedStatic<SecurityUtil> secUtil = Mockito.mockStatic(SecurityUtil.class)) {
            secUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
            mockSecurityContext();

            StatementExportRequest request = new StatementExportRequest(accountId, LocalDate.now().minusDays(1), LocalDate.now());
            service.exportStatement(request, new PrintWriter(sw));
        }

        String output = sw.toString();

        if (randomIban == null || randomIban.length() < 8) {
            assertTrue(output.contains("Account IBAN,INVALID_IBAN"), "Short IBAN did not fallback to INVALID_IBAN");
        } else {
            String expectedMask = randomIban.substring(0, 4) + " **** **** " + randomIban.substring(randomIban.length() - 4);
            assertTrue(output.contains("Account IBAN," + expectedMask), "IBAN was not masked strictly to bank standards");
        }
    }

    // ==================================================================================
    // 3. DATE RANGE BOUNDARY INVARIANT
    // ==================================================================================

    // Test 3: Fixed to sync with production validation logic
    @Property(tries = 500)
    void propertyDateRangeBoundary(
            @ForAll("randomDates") LocalDate start,
            @ForAll("randomDates") LocalDate end) {

        StatementExportRequest request = new StatementExportRequest(accountId, start, end);

        boolean isStartAfterEnd = start.isAfter(end);
        // Sync with production logic: startDate.isBefore(endDate.minusMonths(6))
        boolean exceedsMaxRange = start.isBefore(end.minusMonths(6));

        if (isStartAfterEnd || exceedsMaxRange) {
            assertThrows(IllegalArgumentException.class, () -> request.validateDateRange(6));
        } else {
            assertDoesNotThrow(() -> request.validateDateRange(6));
        }
    }

    @Provide
    Arbitrary<LocalDate> randomDates() {
        // Generating a span of roughly 20 years to trigger all overlaps
        return Arbitraries.longs().between(10000, 20000).map(LocalDate::ofEpochDay);
    }

    // ==================================================================================
    // 4. MONETARY FORMATTING & PRECISION INVARIANT
    // ==================================================================================

    // Test 4: Fixed using Arbitraries.bigDecimals() for high-precision safety
    @Property(tries = 100)
    void propertyMonetaryFormatting(
            @ForAll("randomAmounts") BigDecimal amount,
            @ForAll("randomRates") BigDecimal exchangeRate) {

        String output = executeExportWithMockedEntry("Exchange", "EUR", amount, amount, exchangeRate);

        String[] lines = output.split("\n");
        String dataRow = lines[lines.length - 1];
        String[] columns = dataRow.split(",");

        String formattedAmount = columns[5];
        String formattedRate = columns[8].trim();

        assertTrue(formattedAmount.matches("-?\\d+\\.\\d{4}"), "Amount precision breached Scale 4: " + formattedAmount);
        assertTrue(formattedRate.matches("-?\\d+\\.\\d{6}"), "Exchange Rate precision breached Scale 6: " + formattedRate);
    }

    @Provide
    Arbitrary<BigDecimal> randomAmounts() {
        return Arbitraries.bigDecimals().between(BigDecimal.valueOf(-1000000), BigDecimal.valueOf(1000000)).ofScale(4);
    }

    @Provide
    Arbitrary<BigDecimal> randomRates() {
        return Arbitraries.bigDecimals().between(BigDecimal.valueOf(0.000001), BigDecimal.valueOf(500)).ofScale(6);
    }

    // ==================================================================================
    // 5. CURRENCY WHITELIST ENFORCEMENT INVARIANT
    // ==================================================================================

    @Property(tries = 100)
    void propertyCurrencyWhitelistEnforcement(@ForAll @AlphaChars @StringLength(value = 3) String randomCurrency) {
        String upperCurrency = randomCurrency.toUpperCase();
        String output = executeExportWithMockedEntry("Salary", upperCurrency, BigDecimal.TEN, null, null);

        boolean isSupported = upperCurrency.equals("EUR") || upperCurrency.equals("GBP") || upperCurrency.equals("CHF");

        if (isSupported) {
            assertTrue(output.contains(upperCurrency), "Supported currency was incorrectly stripped");
        } else {
            assertFalse(output.contains(upperCurrency), "Unsupported currency leaked into export: " + upperCurrency);
        }
    }

    // ==================================================================================
    // 6. CSV STRUCTURAL INTEGRITY INVARIANT
    // ==================================================================================

    @Property(tries = 100)
    void propertyCsvStructuralIntegrity(@ForAll @StringLength(min = 0, max = 100) String randomDescription) {
        String output = executeExportWithMockedEntry(randomDescription, "EUR", BigDecimal.TEN, null, null);

        // Find the data row (skipping headers)
        String[] lines = output.split("\n");
        String dataRow = "";
        for (String line : lines) {
            if (line.contains("EUR") && line.contains("CREDIT")) {
                dataRow = line;
                break;
            }
        }

        if (!dataRow.isEmpty()) {
            // Replicate the escaping logic to verify it matches
            String escaped = randomDescription == null ? "" : randomDescription.trim().replace("\"", "\"\"");
            if (escaped.startsWith("=") || escaped.startsWith("+") || escaped.startsWith("-") || escaped.startsWith("@")) {
                escaped = escaped.substring(1).trim();
                escaped = escaped.replace("\"", "\"\"");
            }
            if (escaped.contains(",") || escaped.contains("\n") || escaped.contains("\"")) {
                escaped = "\"" + escaped + "\"";
            }

            assertTrue(dataRow.contains(escaped), "Complex string escaping failed for input: " + randomDescription);
        }
    }

    // ==================================================================================
    // 7. MEMORY STABILITY INVARIANT (PAGINATION)
    // ==================================================================================

    // Test 7: Fixed by providing a populated LedgerEntry to avoid NPE during streaming
    @Property(tries = 10)
    void propertyStreamPaginationLoopsCorrectly(@ForAll @IntRange(min = 1, max = 25) int totalPages) {
        LedgerRepository ledgerRepo = mock(LedgerRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        RateLimiterService rateLimiter = mock(RateLimiterService.class);
        StatementService service = createServiceWithMockEntityManager(ledgerRepo, accountRepo, rateLimiter);

        when(accountRepo.findById(accountId)).thenReturn(Optional.of(createBaseAccount("DE30123456789012345678")));

        // Create a valid entry to avoid NullPointerException in writeLedgerRow
        LedgerEntry validEntry = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .recordedAt(LocalDateTime.now())
                .transactionType(TransactionType.CREDIT)
                .direction("IN")
                .currency("EUR")
                .amount(BigDecimal.TEN)
                .description("PBT Pagination Test")
                .build();

        org.mockito.stubbing.OngoingStubbing<org.springframework.data.domain.Slice<LedgerEntry>> stub =
                when(ledgerRepo.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(any(), any(), any(), any()));

        for (int i = 0; i < totalPages; i++) {
            boolean hasNext = i < (totalPages - 1);
            // Using a concrete PageRequest and a populated list
            stub = stub.thenReturn(new SliceImpl<>(List.of(validEntry), org.springframework.data.domain.PageRequest.of(i, 500), hasNext));
        }

        StringWriter sw = new StringWriter();
        PrintWriter spyWriter = spy(new PrintWriter(sw));

        try (MockedStatic<SecurityUtil> secUtil = Mockito.mockStatic(SecurityUtil.class)) {
            secUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
            mockSecurityContext();

            StatementExportRequest request = new StatementExportRequest(accountId, LocalDate.now().minusDays(1), LocalDate.now());
            service.exportStatement(request, spyWriter);
        }

        // Verify total calls matches total randomized pages
        verify(ledgerRepo, times(totalPages)).findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(any(), any(), any(), any());
        verify(spyWriter, times(totalPages)).flush();
    }

    // ==================================================================================
    // HELPERS
    // ==================================================================================

    private String executeExportWithMockedEntry(String description, String currency, BigDecimal amount, BigDecimal origAmount, BigDecimal rate) {
        LedgerRepository ledgerRepo = mock(LedgerRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        RateLimiterService rateLimiter = mock(RateLimiterService.class);
        StatementService service = createServiceWithMockEntityManager(ledgerRepo, accountRepo, rateLimiter);

        Account account = createBaseAccount("DE30123456789012345678");

        LedgerEntry entry = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .amount(amount)
                .currency(currency)
                .direction("IN")
                .transactionType(TransactionType.CREDIT)
                .description(description)
                .originalAmount(origAmount)
                .exchangeRate(rate)
                .recordedAt(LocalDateTime.now())
                .build();

        when(accountRepo.findById(accountId)).thenReturn(Optional.of(account));
        when(ledgerRepo.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(any(), any(), any(), any()))
                .thenReturn(new SliceImpl<>(List.of(entry)));

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        try (MockedStatic<SecurityUtil> secUtil = Mockito.mockStatic(SecurityUtil.class)) {
            secUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
            mockSecurityContext();

            StatementExportRequest request = new StatementExportRequest(accountId, LocalDate.now().minusDays(1), LocalDate.now());
            service.exportStatement(request, pw);
        }

        return sw.toString();
    }

    private Account createBaseAccount(String iban) {
        User owner = new User(); owner.setId(ownerId);
        Account account = new Account();
        account.setId(accountId);
        account.setUser(owner);
        account.setIban(iban);
        account.setCurrencyCode("EUR");
        return account;
    }

    private void mockSecurityContext() {
        Authentication authentication = mock(Authentication.class);
        SecurityContext securityContext = mock(SecurityContext.class);
        doReturn(Collections.emptyList()).when(authentication).getAuthorities();
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);
    }

    private StatementService createServiceWithMockEntityManager(
            LedgerRepository lr,
            AccountRepository ar,
            RateLimiterService rs) {

        StatementService service = new StatementService(lr, ar, rs);

        // Manual Injection to satisfy the memory-clearing logic
        ReflectionTestUtils.setField(service, "entityManager", mock(EntityManager.class));

        return service;
    }
}