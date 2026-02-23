package com.opayque.api.statement.controller;

import com.opayque.api.statement.dto.StatementExportRequest;
import com.opayque.api.statement.service.StatementService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * REST controller that provides CSV export functionality for account ledger statements.
 * <p>
 * It maps HTTP GET requests under the base path {@code /api/v1/statements} and delegates
 * the actual generation of CSV data to {@link StatementService}. This separation keeps
 * the controller thin, focusing on request validation, HTTP header management and streaming
 * the response, while the service encapsulates business rules such as ledger retrieval,
 * formatting and security considerations.
 * <p>
 * The export endpoint is designed for external integrations and end‑user reporting tools
 * that require a deterministic, timestamped filename and strict content‑type handling.
 * It also adds hardening headers like {@code X-Content-Type-Options: nosniff} to mitigate
 * MIME‑type sniffing attacks.
 *
 * @author Madavan Babu
 * @since 2026
 *
 * @see StatementService
 * @see StatementExportRequest
 */
@RestController
@RequestMapping("/api/v1/statements")
@RequiredArgsConstructor
@Slf4j
public class StatementController {

    private final StatementService statementService;

    /**
     * Streams a CSV export of the requested account's ledger entries.
     * <p>
     * Bound as a GET request where the DTO parameters are provided via the Query String.
     * Example: {@code GET /api/v1/statements/export?accountId=123&startDate=2026-01-01&endDate=2026-01-31}
     *
     * @param request  The validated boundaries and target account for the export.
     * @param response The raw HTTP response to stream the CSV payload into.
     * @throws IOException If the network stream drops or cannot be written to.
     */
    @GetMapping(value = "/export", produces = "text/csv")
    public void exportStatement(
            @Valid StatementExportRequest request,
            HttpServletResponse response
    ) throws IOException {

        // 1. Validate boundaries BEFORE committing the response or getting the writer
        // This allows the GlobalExceptionHandler to correctly map to 400 Bad Request.
        request.validateDateRange(6);

        log.info("HTTP GET /api/v1/statements/export | Account: {}", request.accountId());

        // 1. Generate Secure & Deterministic Filename
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = String.format("opayque_statement_%s_%s.csv", request.accountId(), timestamp);

        // 2. Set strict HTTP Headers for File Download
        response.setContentType("text/csv; charset=utf-8");
        response.setHeader(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");

        // Security Header: Hardcoded string prevents framework versioning conflicts
        response.setHeader("X-Content-Type-Options", "nosniff");

        // 3. Delegate to the Application Service for Streaming
        try {
            statementService.exportStatement(request, response.getWriter());
            log.info("Successfully completed CSV stream transfer for Account: {}", request.accountId());

        } catch (IOException e) {
            log.warn("Client network stream disconnected unexpectedly during export for Account: {}. Message: {}",
                    request.accountId(), e.getMessage());
            throw e;

        } catch (Exception e) {
            log.error("Failed to stream CSV statement for Account: {}. Reason: {}",
                    request.accountId(), e.getMessage(), e);
            throw e;
        }
    }
}