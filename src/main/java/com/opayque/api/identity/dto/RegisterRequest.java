package com.opayque.api.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Epic 1: Identity & Access Management - Story 1.1 User Registration
 * * This record defines the data transfer object for new user registration.
 * It utilizes Jakarta Bean Validation to enforce data integrity before
 * the payload reaches the service layer.
 * * @param email The unique identifier for the user account; must be a valid email format.
 * @param password The raw password; must meet minimum length requirements for "Bank-Grade" security.
 * @param fullName The full legal name of the individual registering.
 */
public record RegisterRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        String password,

        @NotBlank(message = "Full name is required")
        String fullName
) {}