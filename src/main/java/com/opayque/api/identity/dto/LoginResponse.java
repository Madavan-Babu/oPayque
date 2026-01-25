package com.opayque.api.identity.dto;

/**
 * Epic 1: Identity & Access Management - Authentication Response
 * * This record defines the structure of the successful authentication payload.
 * It provides the client with a stateless JWT and specifies the authentication
 * type, following the RFC 6750 Bearer Token usage standard.
 * * @param token The signed, base64-encoded JWT used for subsequent authorized requests.
 * @param type The authentication scheme, defaulted to "Bearer" for standard HTTP Authorization headers.
 */
public record LoginResponse(String token, String type) {

    /**
     * Canonical constructor for simplified token issuance.
     * Automatically assigns the "Bearer" type as the default authentication scheme
     * for the oPayque ecosystem.
     *
     * @param token The stateless JWT issued by the identity service.
     */
    public LoginResponse(String token) {
        this(token, "Bearer");
    }
}