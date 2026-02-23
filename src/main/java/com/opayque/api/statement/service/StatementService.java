package com.opayque.api.statement.service;

import com.opayque.api.infrastructure.exception.ServiceUnavailableException;
import com.opayque.api.infrastructure.ratelimit.RateLimiterService;
import com.opayque.api.infrastructure.util.SecurityUtil;
import com.opayque.api.statement.controller.StatementController;
import com.opayque.api.statement.dto.StatementExportRequest;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.LedgerEntry;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.repository.LedgerRepository;
import com.opayque.api.wallet.service.IbanMetadata;
import io.micrometer.core.annotation.Timed;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * High-performance, memory-safe Statement Export Service.
 * <p>
 * Responsibilities:
 * <ul>
 * <li>Streams chronological ledger data directly to the network buffer to keep heap usage < 200MB.</li>
 * <li>Enforces BOLA (Broken Object Level Authorization) by guaranteeing account ownership.</li>
 * <li>Sanitizes all text fields against CSV/Formula Injection attacks (OWASP mitigation).</li>
 * <li>Excludes unsupported jurisdictions defensively based on the authoritative IbanMetadata registry.</li>
 * </ul>
 *
 * @author Madavan Babu
 * @since 2026
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StatementService {

    private final LedgerRepository ledgerRepository;
    private final AccountRepository accountRepository;
    private final RateLimiterService rateLimiterService;

    @PersistenceContext
    private EntityManager entityManager;

    private static final int CHUNK_SIZE = 500;
    private static final int MAX_MONTHS_RANGE = 6;
    private static final int RATE_LIMIT_QUOTA = 5; // Max 5 exports per minute per user

    // Dynamically synchronized with the authoritative IBAN Jurisdiction Registry
    private static final Set<String> SUPPORTED_CURRENCIES = Arrays.stream(IbanMetadata.values())
            .map(IbanMetadata::getCurrencyCode)
            .collect(Collectors.toUnmodifiableSet());

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * <p>Exports a CSV statement for the specified {@link StatementExportRequest} and writes it to the provided {@link PrintWriter}.</p>
     *
     * <p>The method performs the following steps:</p>
     * <ul>
     *   <li>Validates the request's date range against {@code MAX_MONTHS_RANGE}.</li>
     *   <li>Retrieves the {@link Account} and enforces ownership or admin authorization.</li>
     *   <li>Applies rate limiting via {@link RateLimiterService} to mitigate bulk‑export DoS risks.</li>
     *   <li>Writes masked metadata and column headers to the CSV output.</li>
     *   <li>Streams ledger entries in chunks (defined by {@code CHUNK_SIZE}) using {@link LedgerRepository}, filtering out unsupported currencies and formatting each row according to RFC 4180.</li>
     *   <li>Flushes the writer after each page to free heap space and logs progress.</li>
     * </ul>
     *
     * <p>If an unexpected error occurs during streaming, a {@link ServiceUnavailableException} is thrown to indicate that the export did not complete successfully.</p>
     *
     * @param request the {@link StatementExportRequest} containing {@code accountId},
     *                {@code startDate}, and {@code endDate}; validated before processing.
     * @param writer  the {@link PrintWriter} that receives the CSV data; flushed
     *                after each result page to maintain memory efficiency.
     *
     * @see StatementController
     * @see LedgerRepository
     * @see AccountRepository
     * @see RateLimiterService
     */
    @Timed(value = "opayque.statement.export", description = "Execution time for memory-safe ledger streaming and CSV generation")
    @Transactional(readOnly = true)
    public void exportStatement(StatementExportRequest request, PrintWriter writer) {
        // 1. Validate Input Boundaries
        request.validateDateRange(MAX_MONTHS_RANGE);
        UUID currentUserId = SecurityUtil.getCurrentUserId();

        log.info("Initiating Statement Export | Account: {} | Range: {} to {}",
                request.accountId(), request.startDate(), request.endDate());

        // 2. Fetch Account & Enforce BOLA / RBAC
        Account account = accountRepository.findById(request.accountId())
                .orElseThrow(() -> {
                    log.warn("Export Failed: Account {} not found. Target may have been hard-deleted.", request.accountId());
                    return new IllegalArgumentException("Account not found");
                });

        verifyAuthorization(account, currentUserId);

        // 3. Rate Limiting (Guard against bulk-export DoS attacks)
        rateLimiterService.checkLimit(currentUserId.toString(), "statement_export", RATE_LIMIT_QUOTA);

        try {
            // 4. Write PII-Masked Metadata Header
            writeCsvMetadata(writer, account, request);

            // 5. Write Column Headers
            writer.println("Date (UTC),Transaction ID,Type,Direction,Description,Amount,Currency,Original Amount,Exchange Rate");

            // 6. Execute Streaming Query (The OOM Defense)
            LocalDateTime startBoundary = request.startDate().atStartOfDay();
            LocalDateTime endBoundary = request.endDate().atTime(23, 59, 59);

            Pageable pageable = PageRequest.of(0, CHUNK_SIZE);
            Slice<LedgerEntry> ledgerSlice;
            int totalRecordsProcessed = 0;

            do {
                ledgerSlice = ledgerRepository.findByAccountIdAndRecordedAtBetweenOrderByRecordedAtDesc(
                        request.accountId(), startBoundary, endBoundary, pageable
                );

                for (LedgerEntry entry : ledgerSlice) {
                    // Defensive Jurisdiction Check against authoritative registry
                    if (!SUPPORTED_CURRENCIES.contains(entry.getCurrency())) {
                        log.error("Data Integrity Breach: Unsupported currency '{}' detected in Ledger {}",
                                entry.getCurrency(), entry.getId());
                        continue; // Exclude from export to maintain compliance
                    }

                    writeLedgerRow(writer, entry);
                    totalRecordsProcessed++;
                }

                // Flush buffer to the network layer to free up JVM Heap space
                writer.flush();

                // PRECISION FIX: Detach processed entities from Hibernate L1 Cache
                // This drops the 500 records from the JVM heap before the next fetch.
                entityManager.clear();

                pageable = ledgerSlice.nextPageable();

            } while (ledgerSlice.hasNext());

            log.info("Statement Export Complete | Account: {} | Total Records: {}", request.accountId(), totalRecordsProcessed);

        } catch (Exception e) {
            log.error("CRITICAL: Stream interrupted during CSV generation for Account {}", request.accountId(), e);
            throw new ServiceUnavailableException("Failed to complete statement generation stream.");
        }
    }

    // ==================================================================================
    //                                 PRIVATE HELPERS
    // ==================================================================================

    /**
     * <p>Verifies that the {@code currentUserId} is permitted to access the specified {@link Account}.</p>
     *
     * <p>The authorization check consists of two rules:</p>
     * <ul>
     *   <li>Ownership – the {@link com.opayque.api.identity.entity.User} linked to the {@link Account} has an identifier equal to {@code currentUserId}.</li>
     *   <li>Administrative privilege – the caller possesses the {@code ROLE_ADMIN} authority as evaluated by {@link SecurityUtil}.</li>
     * </ul>
     *
     * <p>If neither rule is satisfied, a security warning is logged and an
     * {@link AccessDeniedException} is thrown to prevent a Broken Object Level
     * Authorization (BOLA) breach.</p>
     *
     * @param account the {@link Account} whose access is being validated; it must include the associated {@link com.opayque.api.identity.entity.User}.
     * @param currentUserId the UUID of the user performing the operation.
     *
     * @see StatementController
     * @see AccountRepository
     * @see SecurityUtil
     */
    private void verifyAuthorization(Account account, UUID currentUserId) {
        boolean isOwner = account.getUser().getId().equals(currentUserId);

        // ARCHITECTURE FIX: Use centralized SecurityUtil instead of direct ContextHolder access
        boolean isAdmin = SecurityUtil.hasAuthority("ROLE_ADMIN");

        if (!isOwner && !isAdmin) {
            log.warn("SECURITY EVENT: BOLA Violation Attempt. User {} attempted to access Account {}",
                    currentUserId, account.getId());
            throw new AccessDeniedException("You do not have permission to access this statement.");
        }
    }

    /**
     * <p>Writes the static header section of a CSV account statement.</p>
     *
     * <p>This metadata supplies essential context for the exported ledger rows,
     * allowing downstream systems (e.g., banking portals or audit tools) to
     * identify the originating {@link Account}, the reporting period, and the
     * generation timestamp. The IBAN is masked via {@link #maskIban(String)} to
     * comply with data‑privacy requirements while still enabling verification of
     * the account reference.</p>
     *
     * @param writer  the {@link PrintWriter} that receives the CSV output; each
     *                invocation appends a new line to the stream.
     * @param account the {@link Account} whose {@code id} and masked {@code iban}
     *                are recorded in the header.
     * @param request the {@link StatementExportRequest} providing the
     *                {@code startDate} and {@code endDate} that define the statement
     *                period.
     *
     * @see StatementController
     * @see LedgerRepository
     * @see AccountRepository
     * @see RateLimiterService
     */
    private void writeCsvMetadata(PrintWriter writer, Account account, StatementExportRequest request) {
        writer.println("--- oPayque Account Statement ---");
        writer.println("Account ID," + account.getId());
        writer.println("Account IBAN," + maskIban(account.getIban()));
        writer.println("Statement Period," + request.startDate() + " to " + request.endDate());
        writer.println("Generated On," + LocalDateTime.now().format(ISO_FORMATTER));
        writer.println("---------------------------------");
    }

    /**
     * <p>Formats a single {@link LedgerEntry} as a CSV row and writes it to the supplied {@link PrintWriter}.</p>
     *
     * <p>The row contains the following fields in strict RFC 4180 order:</p>
     * <ul>
     *   <li>Timestamp of the entry, formatted with {@code ISO_FORMATTER}.</li>
     *   <li>Entry identifier.</li>
     *   <li>Transaction type (enum name).</li>
     *   <li>Direction string.</li>
     *   <li>Sanitized description to prevent CSV injection.</li>
     *   <li>Amount, formatted via {@link #formatAmount(BigDecimal)}.</li>
     *   <li>Currency code.</li>
     *   <li>Original amount, also formatted via {@link #formatAmount(BigDecimal)}.</li>
     *   <li>Exchange rate, formatted via {@link #formatExchangeRate(BigDecimal)}.</li>
     * </ul>
     *
     * <p>This method is a core part of the CSV export process performed by
     * {@link StatementService#exportStatement(StatementExportRequest, PrintWriter)}. By writing one
     * line at a time it enables streaming large data sets without excessive memory consumption.</p>
     *
     * @param writer the {@link PrintWriter} that receives the CSV output; each invocation appends a
     *               new line to the underlying stream.
     * @param entry  the {@link LedgerEntry} whose data is to be serialized into the CSV row.
     *
     * @see StatementController
     * @see LedgerRepository
     * @see StatementService
     */
    private void writeLedgerRow(PrintWriter writer, LedgerEntry entry) {
        String row = String.join(",",
                entry.getRecordedAt().format(ISO_FORMATTER),
                entry.getId().toString(),
                entry.getTransactionType().name(),
                entry.getDirection(),
                sanitizeCsv(entry.getDescription()),
                formatAmount(entry.getAmount()),
                entry.getCurrency(),
                formatAmount(entry.getOriginalAmount()),
                formatExchangeRate(entry.getExchangeRate())
        );
        writer.println(row);
    }

    /**
     * <p>Sanitizes a CSV field to mitigate injection attacks and to comply with RFC 4180.</p>
     *
     * <p>The processing steps are:</p>
     * <ul>
     *   <li>Returns an empty string when {@code input} is {@code null} or blank.</li>
     *   <li>Trims surrounding whitespace.</li>
     *   <li>Strips leading characters that trigger formula evaluation in spreadsheet programs
     *       (<code>=</code>, <code>+</code>, <code>-</code>, <code>@</code>).</li>
     *   <li>Escapes internal double‑quote characters by doubling them.</li>
     *   <li>Wraps the result in double quotes if it contains a comma, newline, or quote,
     *       as required by RFC 4180.</li>
     * </ul>
     *
     * @param input the raw CSV cell content to be sanitized; may be {@code null} or blank.
     * @return a safe CSV representation of {@code input}, or an empty string when the input is {@code null} or blank.
     *
     * @see #writeLedgerRow(PrintWriter, LedgerEntry)
     * @see StatementController
     * @see LedgerRepository
     * @see StatementService
     */
    private String sanitizeCsv(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        String clean = input.trim();
        // Strip leading operators that trigger Excel formula evaluation
        if (clean.startsWith("=") || clean.startsWith("+") || clean.startsWith("-") || clean.startsWith("@")) {
            clean = clean.substring(1).trim();
            log.trace("Sanitized potential CSV injection payload.");
        }

        // Escape internal double quotes by doubling them up, and wrap in outer quotes if commas exist
        String escaped = clean.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\n") || escaped.contains("\"")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }

    /**
     * Applies standard Bank masking rules to the IBAN (e.g., DE30 **** **** 1234).
     * Defensive check included to prevent out-of-bounds exceptions on malformed DB entries.
     */
    private String maskIban(String iban) {
        if (iban == null || iban.length() < 8) {
            log.warn("Malformed IBAN detected during masking process.");
            return "INVALID_IBAN";
        }
        return iban.substring(0, 4) + " **** **** " + iban.substring(iban.length() - 4);
    }

    /**
     * Enforces strict Scale 4 formatting with Bankers Rounding for output consistency.
     */
    private String formatAmount(BigDecimal amount) {
        if (amount == null) return "";
        return amount.setScale(4, RoundingMode.HALF_EVEN).toPlainString();
    }

    /**
     * Retains Precision 6 for exchange rates as defined in the DB schema.
     */
    private String formatExchangeRate(BigDecimal rate) {
        if (rate == null) return "";
        return rate.setScale(6, RoundingMode.HALF_EVEN).toPlainString();
    }
}