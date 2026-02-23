package com.opayque.api.statement.service;

import com.opayque.api.identity.entity.User;
import com.opayque.api.infrastructure.exception.RateLimitExceededException;
import com.opayque.api.infrastructure.ratelimit.RateLimiterService;
import com.opayque.api.infrastructure.util.SecurityUtil;
import com.opayque.api.statement.controller.StatementController;
import com.opayque.api.statement.dto.StatementExportRequest;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.LedgerEntry;
import com.opayque.api.wallet.entity.TransactionType;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.repository.LedgerRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Test suite for {@link StatementService} that verifies the CSV statement export
 * workflow under a wide range of scenarios.
 * <p>
 * The class orchestrates a realistic environment by wiring real repository
 * beans (e.g., {@link LedgerRepository}, {@link AccountRepository}) together with
 * mock collaborators such as {@link RateLimiterService} and a security utility.
 * It then drives the service through the {@code exportStatement} method using
 * {@link StatementExportRequest} instances.
 * <p>
 * <b>Purpose and architectural context</b>
 * <ul>
 *   <li>Ensures that the service enforces the maximum allowed month range for
 *       a date interval, protecting the back‑end from excessively large CSV
 *       generations.</li>
 *   <li>Validates that a missing {@link Account} results in an
 *       {@link IllegalArgumentException}, preserving data‑integrity guarantees.</li>
 *   <li>Confirms that access control is correctly applied:
 *       <ul>
 *         <li>Non‑owner callers without admin privileges receive an
 *             {@link AccessDeniedException}.</li>
 *         <li>Owners and admins are allowed to export successfully.</li>
 *       </ul>
 *   </li>
 *   <li>Tests rate‑limiting behaviour via {@link RateLimiterService} to guarantee
 *       service‑level protection against abuse.</li>
 *   <li>Verifies that sensitive account identifiers (IBANs) are masked, and that
 *       placeholder values are written when the IBAN is {@code null} or malformed.</li>
 *   <li>Exercises CSV sanitisation rules for transaction descriptions, including
 *       stripping dangerous prefixes, escaping quotes, commas, new‑lines and handling
 *       blank or whitespace‑only values.</li>
 *   <li>Checks monetary formatting: null amounts become empty strings, while
 *       non‑null values respect the expected scale and rounding.</li>
 *   <li>Validates pagination handling by iterating over {@link org.springframework.data.domain.Slice}
 *       results until {@code hasNext()} is false.</li>
 *   <li>Ensures that any unexpected database exception is wrapped in a
 *       {@link com.opayque.api.infrastructure.exception.ServiceUnavailableException}, providing a consistent error contract.</li>
 * </ul>
 * <p>
 * Helper methods such as {@code mockSecurityContext(String role)},
 * {@code createMockAccount(String iban)} and {@code createValidRequest()} are used
 * to keep each test focused on a single behavioural aspect.
 * <p>
 * @author Madavan Babu
 * @since 2026
 *
 * @see StatementController
 * @see StatementExportRequest
 * @see AccountRepository
 * @see RateLimiterService
 * @see org.springframework.data.domain.Slice
 */
@ExtendWith(MockitoExtension.class)
class StatementServiceTest {

    @Mock
    private LedgerRepository ledgerRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private RateLimiterService rateLimiterService;

    // Add this mock so Mockito injects a dummy EntityManager
    // to prevent the NPE during the entityManager.clear() call.
    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private StatementService statementService;

    private MockedStatic<SecurityUtil> mockedSecurityUtil;
    private StringWriter stringWriter;
    private PrintWriter printWriter;

    private final UUID accountId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final UUID nonOwnerId = UUID.randomUUID();

    /**
     * Initializes the test environment for {@link StatementServiceTest}.
     *
     * <p>This method performs the essential setup required before each unit test is executed:
     * <ul>
     *   <li>Intercepts static calls to {@link SecurityUtil} using {@code Mockito.mockStatic}
     *       to isolate BOLA (Business Object Level Authorization) checks from the actual
     *       security context.</li>
     *   <li>Creates a {@link StringWriter} and a corresponding {@link PrintWriter}
     *       to capture streamed output generated by the service under test.</li>
     *   <li>Force‑injects the mocked {@code EntityManager} into the {@code statementService}
     *       instance via {@link org.springframework.test.util.ReflectionTestUtils#setField},
     *       ensuring that the service does not encounter a {@code NullPointerException}
     *       caused by missing field injection when {@code @InjectMocks} is insufficient.</li>
     * </ul>
     *
     * @see StatementController
     * @see StatementService
     * @see LedgerRepository
     */
    @BeforeEach
    void setUp() {
        // Intercept static calls to SecurityUtil for BOLA checks
        mockedSecurityUtil = Mockito.mockStatic(SecurityUtil.class);

        // Capture streamed output natively
        stringWriter = new StringWriter();
        printWriter = new PrintWriter(stringWriter);

        // FORCE INJECTION: Manually assign the mock to the service field.
        // This fixes the NPE because @InjectMocks sometimes ignores non-constructor fields.
        org.springframework.test.util.ReflectionTestUtils.setField(statementService, "entityManager", entityManager);
    }

    @AfterEach
    void tearDown() {
        mockedSecurityUtil.close();
        SecurityContextHolder.clearContext();
    }

    // ==================================================================================
    //                                 PRIVATE TEST HELPERS
    // ==================================================================================

    /**
     * Stubs the static {@link SecurityUtil} authority check for unit tests.
     *
     * <p>This method configures the mocked static utility {@code SecurityUtil.hasAuthority}
     * to return {@code true} only when the supplied {@code role} equals
     * {@code "ROLE_ADMIN"}.  By doing so, tests can simulate administrative
     * privileges without invoking the real {@link SecurityContextHolder}
     * and without affecting other security‑related logic.
     *
     * @param role the role to emulate for the current test execution,
     *             for example {@code "ROLE_ADMIN"} or {@code "ROLE_USER"}.
     *
     * @see StatementController
     * @see StatementService
     * @see LedgerRepository
     * @see AccountRepository
     */
    private void mockSecurityContext(String role) {
        // Since we no longer use SecurityContextHolder directly in the Service,
        // we stub the new static utility method instead.
        boolean isAdmin = "ROLE_ADMIN".equals(role);

        mockedSecurityUtil.when(() -> SecurityUtil.hasAuthority("ROLE_ADMIN"))
                .thenReturn(isAdmin);
    }

    /**
     * <p>Creates a lightweight {@link Account} instance populated with the
     * essential fields required for unit‑testing the {@link StatementService}
     * workflow.</p>
     *
     * <p>The generated {@link Account} is linked to a mock {@link User} whose
     * identifier is taken from the test fixture field {@code ownerId}.  It also
     * sets the primary‑key field {@code accountId}, assigns the supplied
     * {@code iban}, and defaults the currency to {@code EUR}.  This provides
     * just enough state for repository stubbing and service verification
     * without persisting the entity.</p>
     *
     * @param iban the International Bank Account Number to assign to the mock
     *             {@link Account}; must be non‑null for the test scenario.
     *
     * @return a fully initialised {@link Account} ready for use in test
     *         assertions or repository mocking.
     *
     * @see StatementService
     * @see AccountRepository
     * @see User
     */
    private Account createMockAccount(String iban) {
        User owner = new User();
        owner.setId(ownerId);

        Account account = new Account();
        account.setId(accountId);
        account.setUser(owner);
        account.setIban(iban);
        account.setCurrencyCode("EUR");
        return account;
    }

    /**
     * <p>Creates a {@link StatementExportRequest} populated with a valid
     * {@code accountId} and a date range that spans from one month prior to the
     * current date up to {@link LocalDate#now()}. This request represents a
     * typical, successful input for the statement export flow used in unit
     * tests.</p>
     *
     * <p>The {@code accountId} value is taken from the test fixture field,
     * while the start and end dates are derived dynamically to keep the
     * request current regardless of when the test is executed.</p>
     *
     * @return a fully‑initialised {@link StatementExportRequest} ready to be
     *         passed to {@link StatementService} for export processing.
     *
     * @see StatementController
     * @see StatementService
     * @see LedgerRepository
     */
    private StatementExportRequest createValidRequest() {
        return new StatementExportRequest(accountId, LocalDate.now().minusMonths(1), LocalDate.now());
    }

    // ==================================================================================
    // 1. PRE-CONDITION & VALIDATION TESTS
    // ==================================================================================

    // Test 1
    @Test
    void testExportStatement_DateRangeExceedsMaxMonths_ThrowsException() {
        StatementExportRequest badRequest = new StatementExportRequest(
                accountId, LocalDate.now().minusMonths(10), LocalDate.now()
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                statementService.exportStatement(badRequest, printWriter)
        );
        assertTrue(ex.getMessage().contains("cannot exceed 6 months"));
    }

    // Test 2
    @Test
    void testExportStatement_AccountNotFound_ThrowsIllegalArgumentException() {
        StatementExportRequest request = createValidRequest();
        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                statementService.exportStatement(request, printWriter)
        );
        assertEquals("Account not found", ex.getMessage());
    }

    // ==================================================================================
    // 2. AUTHORIZATION (BOLA & RBAC) TESTS
    // ==================================================================================

    // Test 3
    @Test
    void testExportStatement_CallerIsNotOwnerAndNotAdmin_ThrowsAccessDeniedException() {
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount("DE30123456789012345678");

        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(nonOwnerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        mockSecurityContext("ROLE_USER");

        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () ->
                statementService.exportStatement(request, printWriter)
        );
        assertTrue(ex.getMessage().contains("permission to access"));
    }

    // Test 4
    @Test
    void testExportStatement_CallerIsOwner_ProceedsSuccessfully() {
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount("DE30123456789012345678");

        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        mockSecurityContext("ROLE_USER");

        // Return empty slice to finish the stream immediately
        when(ledgerRepository.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(
                eq(accountId), any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)
        )).thenReturn(new SliceImpl<>(Collections.emptyList()));

        assertDoesNotThrow(() -> statementService.exportStatement(request, printWriter));
        verify(rateLimiterService).checkLimit(eq(ownerId.toString()), eq("statement_export"), eq(5L));
    }

    // Test 5
    @Test
    void testExportStatement_CallerIsNotOwnerButIsAdmin_ProceedsSuccessfully() {
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount("DE30123456789012345678");

        // The caller is NOT the owner
        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(nonOwnerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        // But the caller IS an Admin
        mockSecurityContext("ROLE_ADMIN");

        when(ledgerRepository.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(
                eq(accountId), any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)
        )).thenReturn(new SliceImpl<>(Collections.emptyList()));

        assertDoesNotThrow(() -> statementService.exportStatement(request, printWriter));
        verify(rateLimiterService).checkLimit(eq(nonOwnerId.toString()), eq("statement_export"), eq(5L));
    }

    // ==================================================================================
    // 3. RATE LIMITING TESTS
    // ==================================================================================

    // Test 6
    @Test
    void testExportStatement_RateLimitExceeded_ThrowsRateLimitException() {
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount("DE30123456789012345678");

        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        mockSecurityContext("ROLE_USER");

        doThrow(new RateLimitExceededException("Rate limit exceeded"))
                .when(rateLimiterService).checkLimit(anyString(), anyString(), anyLong());

        assertThrows(RateLimitExceededException.class, () ->
                statementService.exportStatement(request, printWriter)
        );

        // Verify we never hit the DB to fetch ledger entries
        verify(ledgerRepository, never()).findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(
                any(), any(), any(), any()
        );
    }

    // ==================================================================================
    // 4. IBAN MASKING TESTS
    // ==================================================================================

    // Test 7
    @Test
    void testExportStatement_ValidIban_MasksCorrectly() {
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount("DE30123456789012345678"); // 22 chars

        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        mockSecurityContext("ROLE_USER");
        when(ledgerRepository.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(
                any(), any(), any(), any()
        )).thenReturn(new SliceImpl<>(Collections.emptyList()));

        statementService.exportStatement(request, printWriter);

        String output = stringWriter.toString();
        assertTrue(output.contains("DE30 **** **** 5678"), "IBAN was not masked correctly");
    }

    // Test 8
    @Test
    void testExportStatement_NullIban_WritesInvalidIbanPlaceholder() {
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount(null);

        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        mockSecurityContext("ROLE_USER");
        when(ledgerRepository.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(
                any(), any(), any(), any()
        )).thenReturn(new SliceImpl<>(Collections.emptyList()));

        statementService.exportStatement(request, printWriter);

        String output = stringWriter.toString();
        assertTrue(output.contains("Account IBAN,INVALID_IBAN"));
    }

    // Test 9
    @Test
    void testExportStatement_ShortIban_WritesInvalidIbanPlaceholder() {
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount("DE30"); // Less than 8 chars

        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        mockSecurityContext("ROLE_USER");
        when(ledgerRepository.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(
                any(), any(), any(), any()
        )).thenReturn(new SliceImpl<>(Collections.emptyList()));

        statementService.exportStatement(request, printWriter);

        String output = stringWriter.toString();
        assertTrue(output.contains("Account IBAN,INVALID_IBAN"));
    }

    // ==================================================================================
    // 5. CSV INJECTION & SANITIZATION TESTS
    // ==================================================================================

    // Test 10
    @Test
    void testExportStatement_DescriptionNullOrBlank_WritesEmptyString() {
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount("DE30123456789012345678");

        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        mockSecurityContext("ROLE_USER");

        // Create a Ledger Entry with a NULL description
        LedgerEntry entry = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .account(account)
                .amount(new BigDecimal("100.00"))
                .currency("EUR")
                .direction("IN")
                .transactionType(TransactionType.CREDIT)
                .description(null) // Target under test
                .recordedAt(LocalDateTime.now())
                .build();

        when(ledgerRepository.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(
                any(), any(), any(), any()
        )).thenReturn(new SliceImpl<>(List.of(entry)));

        statementService.exportStatement(request, printWriter);

        String output = stringWriter.toString();

        // Assert the description column (index 4) is perfectly empty between the commas
        // Expected format snippet: IN,,100.0000,EUR
        assertTrue(output.contains("IN,,100.0000,EUR"), "Null description failed to format as empty string");
    }

    // Test 11
    @Test
    void testExportStatement_DescriptionWithDangerousPrefixes_StripsPrefixes() {
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount("DE30123456789012345678");

        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        mockSecurityContext("ROLE_USER");

        // Simulate an OWASP CSV Injection payload
        LedgerEntry entry = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .account(account)
                .amount(new BigDecimal("10.00"))
                .currency("EUR")
                .direction("OUT")
                .transactionType(TransactionType.DEBIT)
                .description("=cmd|' /C calc'!A0") // Malicious prefix '='
                .recordedAt(LocalDateTime.now())
                .build();

        when(ledgerRepository.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(
                any(), any(), any(), any()
        )).thenReturn(new SliceImpl<>(List.of(entry)));

        statementService.exportStatement(request, printWriter);

        String output = stringWriter.toString();
        // The '=' should be completely stripped by sanitizeCsv
        assertTrue(output.contains("cmd|' /C calc'!A0"), "Dangerous prefix was not stripped");
        assertFalse(output.contains("=cmd|' /C calc'!A0"), "Injection payload leaked into output");
    }

    // Test 12
    @Test
    void testExportStatement_DescriptionWithCommasQuotesNewlines_EscapesProperly() {
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount("DE30123456789012345678");

        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        mockSecurityContext("ROLE_USER");

        LedgerEntry entry = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .account(account)
                .amount(new BigDecimal("15.00"))
                .currency("EUR")
                .direction("OUT")
                .transactionType(TransactionType.DEBIT)
                .description("User said \"Hello\", then\ntransferred") // Commas, quotes, and newlines
                .recordedAt(LocalDateTime.now())
                .build();

        when(ledgerRepository.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(
                any(), any(), any(), any()
        )).thenReturn(new SliceImpl<>(List.of(entry)));

        statementService.exportStatement(request, printWriter);

        String output = stringWriter.toString();
        // RFC 4180: Must wrap in outer quotes, and inner quotes must be doubled
        assertTrue(output.contains("\"User said \"\"Hello\"\", then\ntransferred\""),
                "Complex string was not escaped according to CSV RFC 4180 standards");
    }

    // ==================================================================================
    // 6. CURRENCY & DATA INTEGRITY TESTS
    // ==================================================================================

    // Test 13
    @Test
    void testExportStatement_ContainsUnsupportedCurrency_SkipsRowAndLogsError() {
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount("DE30123456789012345678");

        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        mockSecurityContext("ROLE_USER");

        LedgerEntry validEntry1 = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .amount(BigDecimal.TEN)
                .currency("EUR") // Supported
                .direction("IN")
                .transactionType(TransactionType.CREDIT)
                .description("Valid EUR")
                .recordedAt(LocalDateTime.now())
                .build();

        LedgerEntry invalidEntry = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .amount(BigDecimal.TEN)
                .currency("USD") // UNSUPPORTED
                .direction("IN")
                .transactionType(TransactionType.CREDIT)
                .description("Rogue USD")
                .recordedAt(LocalDateTime.now())
                .build();

        LedgerEntry validEntry2 = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .amount(BigDecimal.TEN)
                .currency("GBP") // Supported
                .direction("IN")
                .transactionType(TransactionType.CREDIT)
                .description("Valid GBP")
                .recordedAt(LocalDateTime.now())
                .build();

        when(ledgerRepository.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(
                any(), any(), any(), any()
        )).thenReturn(new SliceImpl<>(List.of(validEntry1, invalidEntry, validEntry2)));

        statementService.exportStatement(request, printWriter);

        String output = stringWriter.toString();
        assertTrue(output.contains("Valid EUR"));
        assertTrue(output.contains("Valid GBP"));
        assertFalse(output.contains("Rogue USD"), "Unsupported currency leaked into export!");
    }

    // ==================================================================================
    // 7. AMOUNT FORMATTING TESTS
    // ==================================================================================

    // Test 14
    @Test
    void testExportStatement_WithNullAmounts_FormatsAsEmptyStrings() {
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount("DE30123456789012345678");

        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        mockSecurityContext("ROLE_USER");

        LedgerEntry entry = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .amount(new BigDecimal("100.00"))
                .currency("EUR")
                .direction("IN")
                .transactionType(TransactionType.CREDIT)
                .description("Deposit")
                .originalAmount(null) // Null field 1
                .exchangeRate(null)   // Null field 2
                .recordedAt(LocalDateTime.now())
                .build();

        when(ledgerRepository.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(
                any(), any(), any(), any()
        )).thenReturn(new SliceImpl<>(List.of(entry)));

        statementService.exportStatement(request, printWriter);

        String output = stringWriter.toString();
        // The last two columns should be empty (ends with a comma or empty space between commas)
        assertTrue(output.contains("EUR,,"), "Null amounts did not format to empty strings properly");
    }

    // Test 15
    @Test
    void testExportStatement_WithNonNullAmounts_EnforcesCorrectScales() {
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount("DE30123456789012345678");

        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        mockSecurityContext("ROLE_USER");

        LedgerEntry entry = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .amount(new BigDecimal("100.5")) // Should become 100.5000
                .currency("EUR")
                .direction("IN")
                .transactionType(TransactionType.CREDIT)
                .description("Exchange")
                .originalAmount(new BigDecimal("120.3333333")) // Should become 120.3333
                .exchangeRate(new BigDecimal("0.8543219"))     // Should become 0.854322
                .recordedAt(LocalDateTime.now())
                .build();

        when(ledgerRepository.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(
                any(), any(), any(), any()
        )).thenReturn(new SliceImpl<>(List.of(entry)));

        statementService.exportStatement(request, printWriter);

        String output = stringWriter.toString();
        assertTrue(output.contains("100.5000,EUR,120.3333,0.854322"),
                "Decimal scaling and Bankers Rounding rules were not applied correctly");
    }

    // ==================================================================================
    // 8. STREAM PAGINATION TESTS
    // ==================================================================================

    // Test 16
    @Test
    void testExportStatement_MultiplePages_LoopsUntilHasNextIsFalse() {
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount("DE30123456789012345678");

        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        mockSecurityContext("ROLE_USER");

        LedgerEntry entry1 = LedgerEntry.builder()
                .id(UUID.randomUUID()).amount(BigDecimal.ONE).currency("EUR").direction("IN")
                .transactionType(TransactionType.CREDIT).description("Page 1").recordedAt(LocalDateTime.now()).build();

        LedgerEntry entry2 = LedgerEntry.builder()
                .id(UUID.randomUUID()).amount(BigDecimal.ONE).currency("EUR").direction("IN")
                .transactionType(TransactionType.CREDIT).description("Page 2").recordedAt(LocalDateTime.now()).build();

        // Page 1: Has next page (true)
        org.springframework.data.domain.Slice<LedgerEntry> slice1 =
                new SliceImpl<>(List.of(entry1), org.springframework.data.domain.PageRequest.of(0, 500), true);

        // Page 2: Last page (false)
        org.springframework.data.domain.Slice<LedgerEntry> slice2 =
                new SliceImpl<>(List.of(entry2), org.springframework.data.domain.PageRequest.of(1, 500), false);

        // Chain the mock returns
        when(ledgerRepository.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(
                any(), any(), any(), any()
        )).thenReturn(slice1).thenReturn(slice2);

        statementService.exportStatement(request, printWriter);

        // Verify the repository was hit exactly twice with pagination moving forward
        verify(ledgerRepository, times(2)).findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(
                any(), any(), any(), any()
        );

        String output = stringWriter.toString();
        assertTrue(output.contains("Page 1"));
        assertTrue(output.contains("Page 2"));
    }

    // ==================================================================================
    // 9. EXCEPTION HANDLING & RESILIENCE TESTS
    // ==================================================================================

    // Test 17
    @Test
    void testExportStatement_DatabaseThrowsException_WrapsInServiceUnavailableException() {
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount("DE30123456789012345678");

        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        mockSecurityContext("ROLE_USER");

        // Simulate a dropped database connection mid-stream
        when(ledgerRepository.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(
                any(), any(), any(), any()
        )).thenThrow(new RuntimeException("Connection reset by peer"));

        com.opayque.api.infrastructure.exception.ServiceUnavailableException ex =
                assertThrows(com.opayque.api.infrastructure.exception.ServiceUnavailableException.class, () ->
                        statementService.exportStatement(request, printWriter)
                );

        assertEquals("Failed to complete statement generation stream.", ex.getMessage());
    }

    //Test 18
    @Test
    void testExportStatement_DescriptionWithOnlyNewline_WrapsInQuotes() {
        // 1. Arrange
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount("DE30123456789012345678");

        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        // CORRECTED: Using ROLE_CUSTOMER to match your security configuration
        mockSecurityContext("ROLE_CUSTOMER");

        LedgerEntry entry = LedgerEntry.builder()
                .id(UUID.randomUUID()) // FIXED: Set ID to prevent the NullPointerException in writeLedgerRow
                .description("Line1\nLine2") // THE TARGET BRANCH
                .amount(BigDecimal.TEN)
                .currency("EUR")
                .direction("IN")
                .transactionType(TransactionType.CREDIT)
                .recordedAt(LocalDateTime.now())
                .build();

        when(ledgerRepository.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(any(), any(), any(), any()))
                .thenReturn(new SliceImpl<>(List.of(entry)));

        // 2. Act
        statementService.exportStatement(request, printWriter);

        // 3. Assert
        String output = stringWriter.toString();
        // RFC 4180 requires wrapping the field in double quotes when a newline is present
        assertTrue(output.contains("\"Line1\nLine2\""), "Newline failed to trigger RFC 4180 wrapping");
    }

    // ==================================================================================
    // BRANCH COVERAGE: sanitizeCsv()
    // ==================================================================================

    //Test 19
    @Test
    void testExportStatement_DescriptionIsBlankSpaces_WritesEmptyString() {
        // Hits Branch: input.isBlank() (The second half of the first IF)
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount("DE30123456789012345678");
        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        mockSecurityContext("ROLE_CUSTOMER");

        LedgerEntry entry = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .description("   ") // Only whitespaces
                .amount(BigDecimal.TEN).currency("EUR").direction("IN")
                .transactionType(TransactionType.CREDIT).recordedAt(LocalDateTime.now()).build();

        when(ledgerRepository.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(any(), any(), any(), any()))
                .thenReturn(new SliceImpl<>(List.of(entry)));

        statementService.exportStatement(request, printWriter);

        // Assert it collapsed the blank string to an empty field (,,)
        assertTrue(stringWriter.toString().contains("IN,,10.0000,EUR"));
    }

    //Test 20
    @Test
    void testExportStatement_DescriptionStartsWithPlus_StripsPrefix() {
        // Hits Branch: clean.startsWith("+") (The second condition of the prefix IF)
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount("DE30123456789012345678");
        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        mockSecurityContext("ROLE_CUSTOMER");

        LedgerEntry entry = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .description("+SUM(A1:B1)")
                .amount(BigDecimal.TEN).currency("EUR").direction("IN")
                .transactionType(TransactionType.CREDIT).recordedAt(LocalDateTime.now()).build();

        when(ledgerRepository.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(any(), any(), any(), any()))
                .thenReturn(new SliceImpl<>(List.of(entry)));

        statementService.exportStatement(request, printWriter);

        assertTrue(stringWriter.toString().contains("SUM(A1:B1)"));
        assertFalse(stringWriter.toString().contains("+SUM"));
    }

    //Test 21
    @Test
    void testExportStatement_DescriptionStartsWithMinus_StripsPrefix() {
        // Hits Branch: clean.startsWith("-") (The third condition of the prefix IF)
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount("DE30123456789012345678");
        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        mockSecurityContext("ROLE_CUSTOMER");

        LedgerEntry entry = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .description("-100 USD")
                .amount(BigDecimal.TEN).currency("EUR").direction("IN")
                .transactionType(TransactionType.CREDIT).recordedAt(LocalDateTime.now()).build();

        when(ledgerRepository.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(any(), any(), any(), any()))
                .thenReturn(new SliceImpl<>(List.of(entry)));

        statementService.exportStatement(request, printWriter);

        assertTrue(stringWriter.toString().contains("100 USD"));
        assertFalse(stringWriter.toString().contains("-100"));
    }

    //Test 22
    @Test
    void testExportStatement_DescriptionStartsWithAt_StripsPrefix() {
        // Hits Branch: clean.startsWith("@") (The fourth condition of the prefix IF)
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount("DE30123456789012345678");
        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        mockSecurityContext("ROLE_CUSTOMER");

        LedgerEntry entry = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .description("@cmd|' /C calc'")
                .amount(BigDecimal.TEN).currency("EUR").direction("IN")
                .transactionType(TransactionType.CREDIT).recordedAt(LocalDateTime.now()).build();

        when(ledgerRepository.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(any(), any(), any(), any()))
                .thenReturn(new SliceImpl<>(List.of(entry)));

        statementService.exportStatement(request, printWriter);

        assertTrue(stringWriter.toString().contains("cmd|"));
        assertFalse(stringWriter.toString().contains("@cmd"));
    }

    //Test 23
    @Test
    void testExportStatement_DescriptionWithOnlyQuotes_EscapesProperly() {
        // Hits Branch: escaped.contains("\"") (The third condition of the wrapper IF)
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount("DE30123456789012345678");
        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        mockSecurityContext("ROLE_CUSTOMER");

        LedgerEntry entry = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .description("He said \"Hello\" to me") // Quotes without commas or newlines
                .amount(BigDecimal.TEN).currency("EUR").direction("IN")
                .transactionType(TransactionType.CREDIT).recordedAt(LocalDateTime.now()).build();

        when(ledgerRepository.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(any(), any(), any(), any()))
                .thenReturn(new SliceImpl<>(List.of(entry)));

        statementService.exportStatement(request, printWriter);

        // RFC 4180 expects the string to be wrapped in outer quotes, and inner quotes doubled
        assertTrue(stringWriter.toString().contains("\"He said \"\"Hello\"\" to me\""));
    }

    //Test 24
    @Test
    void testExportStatement_DescriptionIsClean_ReturnsUnmodifiedString() {
        // Hits the final fallback branch: return escaped; (and the closing brace)
        StatementExportRequest request = createValidRequest();
        Account account = createMockAccount("DE30123456789012345678");
        mockedSecurityUtil.when(SecurityUtil::getCurrentUserId).thenReturn(ownerId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        mockSecurityContext("ROLE_CUSTOMER");

        LedgerEntry entry = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .description("Coffee Shop Purchase") // Safe text. No prefixes, commas, quotes, or newlines.
                .amount(BigDecimal.TEN).currency("EUR").direction("OUT")
                .transactionType(TransactionType.DEBIT).recordedAt(LocalDateTime.now()).build();

        when(ledgerRepository.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(any(), any(), any(), any()))
                .thenReturn(new SliceImpl<>(List.of(entry)));

        statementService.exportStatement(request, printWriter);

        // Assert the description remains completely untouched and isn't wrapped in quotes
        assertTrue(stringWriter.toString().contains("OUT,Coffee Shop Purchase,10.0000,EUR"),
                "Clean string should pass through the sanitizer unmodified.");
    }
}