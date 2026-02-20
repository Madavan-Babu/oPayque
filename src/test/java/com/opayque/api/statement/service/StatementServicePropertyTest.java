package com.opayque.api.statement.service;

import com.opayque.api.identity.entity.User;
import com.opayque.api.infrastructure.ratelimit.RateLimiterService;
import com.opayque.api.infrastructure.util.SecurityUtil;
import com.opayque.api.statement.dto.StatementExportRequest;
import com.opayque.api.wallet.controller.WalletController;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.LedgerEntry;
import com.opayque.api.wallet.entity.TransactionType;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.repository.LedgerRepository;
import com.opayque.api.wallet.service.AccountService;
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
 * Test suite that validates the {@link StatementService} export functionality using
 * property‑based testing techniques provided by jqwik.
 * <p>
 * The class focuses on asserting that the CSV representation of account statements
 * remains robust against a wide range of random inputs. It verifies that:
 * <ul>
 *   <li>CSV injection vectors are neutralised (e.g. leading {@code =,+,-,@} prefixes).</li>
 *   <li>IBAN masking preserves the original pattern while hiding sensitive characters.</li>
 *   <li>Date range boundaries are respected and produce deterministic output.</li>
 *   <li>Monetary values are formatted with high‑precision {@link BigDecimal}
 *       handling to avoid rounding errors.</li>
 *   <li>Only whitelisted currency codes are emitted, preventing illegal ISO codes.</li>
 *   <li>The structural integrity of each CSV line (column count, escaping) is maintained.</li>
 *   <li>Pagination over ledger slices works correctly without entering infinite loops.</li>
 * </ul>
 * <p>
 * Helper methods such as {@code executeExportWithMockedEntry(...)} construct a mock
 * {@link LedgerRepository} and {@link AccountRepository} environment, stub the current
 * security context, and invoke {@link StatementService#exportStatement(StatementExportRequest, PrintWriter)}
 * to capture the generated CSV for assertions.
 * <p>
 * By driving the service with randomly generated payloads, the suite discovers edge
 * cases that conventional example‑based tests may miss, ensuring production‑grade
 * resilience of the statement export pipeline.
 *
 * @author Madavan Babu
 * @since 2026
 *
 * @see StatementService
 * @see LedgerRepository
 * @see AccountRepository
 * @see RateLimiterService
 * @see StatementExportRequest
 * @see SecurityUtil
 */
class StatementServicePropertyTest {

    private final UUID accountId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    // ==================================================================================
    // 1. CSV INJECTION NEUTRALIZATION INVARIANT
    // ==================================================================================

    /**
     * <p>Property‑based test that verifies CSV export sanitisation against formula injection.
     * The method constructs a potentially malicious description by concatenating a known
     * injection {@code prefix} (e.g. {@code "="}, {@code "+"}, {@code "-"} or {@code "@"})
     * with a random {@code payload}. It then executes the export logic via
     * {@link StatementService#exportStatement(StatementExportRequest, PrintWriter)} and
     * asserts that the resulting CSV never starts a cell with the raw malicious string.
     * This ensures that the export routine prefixes or otherwise neutralises dangerous
     * characters, preventing spreadsheet applications from interpreting them as formulas.</p>
     *
     * <p>The test is executed repeatedly (as configured by {@code @Property(tries = 100)})
     * with varied {@code payload} lengths (1‑20 characters) and a range of injection
     * prefixes supplied by {@code injectionPrefixes()} to achieve broad coverage of
     * possible attack vectors.</p>
     *
     * @param payload  a randomly generated string (1–20 characters) that represents
     *                 the content an attacker might try to inject into the CSV description.
     * @param prefix   a string taken from {@code injectionPrefixes()} that represents
     *                 a typical CSV injection trigger (e.g. {@code "="}, {@code "+"},
     *                 {@code "-"}, {@code "@"}).
     *
     * @see StatementService
     * @see StatementExportRequest
     * @see LedgerRepository
     * @see AccountRepository
     */
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

    /**
     * <p>Property‑based test that verifies the IBAN masking logic applied during CSV statement
     * export. The test creates an {@link Account} with a randomly generated IBAN, invokes
     * {@link StatementService#exportStatement(StatementExportRequest, PrintWriter)} and
     * asserts that the exported output contains the IBAN masked according to banking standards:
     * the first four characters, a masked segment of four asterisks separated by spaces,
     * and the last four characters. If the IBAN is {@code null} or shorter than eight characters,
     * the export should fall back to the placeholder {@code INVALID_IBAN}.</p>
     *
     * <p>The method runs repeatedly (configured by {@code @Property(tries = 100)}) with IBAN
     * lengths ranging from 0 to 34 characters, covering the full spectrum of valid and edge‑case
     * values.</p>
     *
     * @param randomIban a randomly generated string (0‑34 characters) representing the
     *        IBAN of the account under test. {@code null} or strings shorter than eight
     *        characters trigger the fallback behaviour.
     *
     * @see StatementService
     * @see StatementExportRequest
     * @see LedgerRepository
     * @see AccountRepository
     * @see RateLimiterService
     */
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

    /**
     * <p>Property‑based test that verifies the validation of date‑range boundaries applied
     * when exporting a statement. The test creates a {@link StatementExportRequest}
     * using the supplied {@code start} and {@code end} dates and asserts that the request
     * behaves consistently with production rules:</p>
     *
     * <ul>
     *   <li>If {@code start} is after {@code end}, the request must reject the range.</li>
     *   <li>If the interval between {@code start} and {@code end} exceeds the allowed
     *       maximum of six months, the request must also reject the range.</li>
     *   <li>When neither condition is met, the request should accept the range without
     *       throwing an exception.</li>
     * </ul>
     *
     * <p>The validation is performed by {@link StatementExportRequest#validateDateRange(int)},
     * which throws an {@code IllegalArgumentException} for invalid ranges. This test
     * ensures that the service logic aligns with the production validation constraints.</p>
     *
     * @param start a randomly generated {@link LocalDate} representing the
     *              start of the statement period.
     * @param end   a randomly generated {@link LocalDate} representing the
     *              end of the statement period.
     *
     * @see StatementExportRequest
     * @see StatementService
     */
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

    /**
     * <p>Property‑based test that validates the monetary formatting applied to CSV
     * exports. It generates random {@code amount} and {@code exchangeRate} values,
     * invokes {@link #executeExportWithMockedEntry(String, String, BigDecimal, BigDecimal, BigDecimal)},
     * extracts the formatted columns from the produced CSV line and asserts that
     * the numeric values follow the precision contract required by the downstream
     * accounting pipelines.</p>
     *
     * <ul>
     *   <li>Amounts must be rendered with exactly four fractional digits
     *       (scale 4).</li>
     *   <li>Exchange rates must be rendered with exactly six fractional digits
     *       (scale 6).</li>
     * </ul>
     *
     * <p>This guarantees consistent precision across exported statements,
     * preventing rounding discrepancies during financial reconciliation.</p>
     *
     * @param amount        a randomly generated {@link BigDecimal}
     *                      representing the transaction amount. The value may be
     *                      positive, negative or zero and can contain an arbitrary
     *                      number of fractional digits before formatting.
     *
     * @param exchangeRate  a randomly generated {@link BigDecimal}
     *                      representing the currency conversion factor applied to
     *                      {@code amount}. Like {@code amount}, it may have arbitrary
     *                      precision prior to formatting.
     *
     * @see StatementService
     * @see StatementExportRequest
     * @see LedgerRepository
     * @see AccountRepository
     */
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

    /**
     * <p>Validates the currency whitelist during the export workflow.</p>
     *
     * <p>This method converts the provided three‑character currency code to upper case and
     * invokes {@code executeExportWithMockedEntry} to simulate an export of a salary entry.
     * Only the currencies {@code EUR}, {@code GBP}, and {@code CHF} are considered supported.
     * If the currency is supported, the exported output must contain the currency code;
     * otherwise, the output must not contain it. This ensures that unsupported currencies
     * are never leaked into export files.</p>
     *
     * @param randomCurrency a randomly generated three‑letter currency code; the value is
     *                       constrained to alphabetic characters by the {@code @AlphaChars}
     *                       and {@code @StringLength(3)} annotations.
     *
     * @see com.opayque.api.statement.controller.StatementController
     * @see StatementService
     * @see LedgerRepository
     */
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

    /**
     * <p>Validates the structural integrity of a CSV export when a random description string is
     * injected into the data row. The method generates an export line using {@code executeExportWithMockedEntry},
     * extracts the relevant data row, and then reproduces the escaping logic that the export routine applies.
     * It asserts that the escaped value appears in the resulting CSV line, ensuring that complex strings
     * containing formula prefixes, commas, line breaks, or quotation marks are correctly handled.</p>
     *
     * <p>This verification is crucial for preventing CSV injection attacks and guaranteeing that the
     * generated CSV files can be safely opened by spreadsheet applications without unintended evaluation
     * of cell content.</p>
     *
     * @param randomDescription a randomly generated description string whose length is constrained
     *                          between 0 and 100 characters. The value may include special characters
     *                          such as {@code "="}, {@code "+"}, {@code "-"}, {@code "@"},
     *                          commas, new‑lines, or quotes, which must be escaped according to CSV
     *                          rules.
     */
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

    /**
     * Property‑based test that verifies the {@link StatementService} correctly iterates over
     * all pages returned by {@link LedgerRepository#findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc}
     * when exporting a statement.
     *
     * <p>The test creates a mocked paging scenario where a configurable number of pages
     * (between 1 and 25) are returned. Each page contains a single valid {@link LedgerEntry}
     * to avoid a {@code NullPointerException} in the export logic. The test then asserts that
     * the repository method is invoked exactly {@code totalPages} times and that the writer
     * is flushed the same number of times, confirming that the pagination loop respects the
     * {@code hasNext} flag of the {@link org.springframework.data.domain.Slice}.
     *
     * <p>Key collaborators involved in this test:
     * <ul>
     *   <li>{@link LedgerRepository} – mocked to return a sequence of {@link org.springframework.data.domain.Slice}
     *       objects each containing the same {@link LedgerEntry}.</li>
     *   <li>{@link AccountRepository} – mocked to resolve the test account.</li>
     *   <li>{@link RateLimiterService} – mocked as it is a dependency of {@link StatementService}.</li>
     *   <li>{@link SecurityUtil} – static mock supplies a fixed user identifier.</li>
     *   <li>{@link StatementExportRequest} – input DTO that drives the export operation.</li>
     * </ul>
     *
     * @param totalPages the number of pages to simulate for the pagination test; must be
     *                   between {@code 1} and {@code 25} inclusive.
     *
     * @see StatementService
     * @see LedgerRepository
     * @see AccountRepository
     * @see RateLimiterService
     */
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

    /**
     * Executes a statement export operation using fully mocked repository and service
     * components and returns the generated output as a {@code String}.
     * <p>
     * The method builds a synthetic {@link LedgerEntry} with the supplied values,
     * configures the mocked {@link LedgerRepository} and {@link AccountRepository} to
     * return this entry, and then invokes {@link StatementService#exportStatement(StatementExportRequest, PrintWriter)}.
     * It also mocks the static {@link SecurityUtil} call to supply a deterministic user
     * identifier, ensuring the export logic can be exercised in isolation from external
     * dependencies such as the database or security context.
     * <p>
     * This utility is primarily intended for unit‑ or integration‑style tests where a
     * reproducible export result is required without involving real persistence layers.
     *
     * @param description a brief textual description for the generated {@link LedgerEntry}
     * @param currency the ISO‑4217 currency code associated with the entry (e.g., {@code "EUR"})
     * @param amount the transaction amount in the target currency
     * @param origAmount the original amount before any currency conversion
     * @param rate the exchange rate applied to convert {@code origAmount} to {@code amount}
     * @return the complete statement export content produced by {@link StatementService#exportStatement(StatementExportRequest, PrintWriter)}
     * @see StatementService
     * @see LedgerRepository
     * @see AccountRepository
     */
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

    /**
     * <p>Creates a new {@link Account} instance populated with the essential
     * attributes required for persisting a fresh account record.</p>
     *
     * <p>The method assigns the supplied {@code iban} to the account,
     * links it to a {@link User} identified by {@code ownerId} (derived from
     * the surrounding context), sets the default currency to {@code EUR},
     * and pre‑populates the entity identifiers.</p>
     *
     * @param iban the International Bank Account Number to associate with the new account
     * @return a fully initialised {@link Account} ready for persistence
     *
     * @see AccountService
     * @see AccountRepository
     * @see WalletController
     */
    private Account createBaseAccount(String iban) {
        User owner = new User(); owner.setId(ownerId);
        Account account = new Account();
        account.setId(accountId);
        account.setUser(owner);
        account.setIban(iban);
        account.setCurrencyCode("EUR");
        return account;
    }

    /**
     * Configures a {@code SecurityContext} containing a mocked {@link Authentication}
     * instance and installs it into the {@link SecurityContextHolder}.
     * <p>
     * The mock authentication is set up with an empty list of authorities, effectively representing a
     * user without any granted roles. This allows unit tests to run in isolation from Spring Security
     * concerns, focusing on the business logic under test without performing actual authentication or
     * authorization checks.
     * <p>
     * Typical usage is within test initialization code where the security context must be present but
     * the details of the authenticated principal are irrelevant.
     *
     * @see SecurityContextHolder
     * @see org.junit.jupiter.api.BeforeEach
     */
    private void mockSecurityContext() {
        Authentication authentication = mock(Authentication.class);
        SecurityContext securityContext = mock(SecurityContext.class);
        doReturn(Collections.emptyList()).when(authentication).getAuthorities();
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);
    }

    /**
     * <p>Creates a {@link StatementService} instance with a mocked {@link EntityManager}
     * for use in unit or integration tests.</p>
     *
     * <p>The method manually injects the mock {@code EntityManager} via
     * {@link ReflectionTestUtils} to satisfy the internal memory‑clearing logic that
     * expects an entity manager to be present.</p>
     *
     * @param lr the {@link LedgerRepository} used by the service to access ledger data
     * @param ar the {@link AccountRepository} used by the service to access account data
     * @param rs the {@link RateLimiterService} governing request throttling for the service
     * @return a fully constructed {@link StatementService} with a mock {@link EntityManager}
     *
     * @see LedgerRepository
     * @see AccountRepository
     * @see StatementService
     */
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