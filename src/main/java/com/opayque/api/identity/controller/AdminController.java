package com.opayque.api.identity.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// Epic 1: Story 1.3 - RBAC Verification Endpoint
///
/// This controller serves as a high-security zone to verify that our
/// Role-Based Access Control (RBAC) gates are functioning correctly.
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    /// Protected Endpoint: System Statistics
    ///
    /// * **RBAC Gate**: Only users with 'ROLE_ADMIN' can execute this method.
    /// * **Security**: If a 'CUSTOMER' attempts access, Spring Security blocks it
    ///   before the method is even entered (403 Forbidden).
    @GetMapping("/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> getSystemStats() {
        log.info("Admin access granted to system stats.");
        // In a real app, this would return CPU/Memory/Transaction volume
        return ResponseEntity.ok("System Operational: [Green]");
    }
}