package com.opayque.api.identity.service;

import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/// Epic 1: Identity & Access Management - User Details Logic Testing
///
/// This suite validates the bridge between the identity ledger and the security framework.
/// It ensures that {@link CustomUserDetailsService} correctly maps persisted user entities
/// to Spring Security's internal user contracts.
///
/// ### Verification Focus:
/// - **Identity Mapping**: Confirming the email serves as the unique identifier (Username).
/// - **Fault Tolerance**: Verifying that missing identities trigger the standard security exception.
@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CustomUserDetailsService userDetailsService;

    /// Verifies that a valid user record is correctly retrieved and transformed
    /// into a {@link UserDetails} object for the security filter chain.
    @Test
    @DisplayName("Unit: Should load UserDetails by email identifier")
    void shouldLoadUserByUsername() {
        // Arrange: Mock a persisted user identity
        String email = "test@opayque.com";
        User user = User.builder().email(email).build();
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        // Act: Invoke the details service
        UserDetails result = userDetailsService.loadUserByUsername(email);

        // Assert: Confirm the mapping from Email -> Username is intact
        assertEquals(email, result.getUsername());
    }

    /// Ensures that searches for non-existent identities result in a
    /// {@link UsernameNotFoundException}, adhering to Spring Security's internal standards.
    @Test
    @DisplayName("Unit: Should throw UsernameNotFoundException when identity does not exist")
    void shouldThrowWhenUserNotFound() {
        // Arrange: Simulate an empty search result in the ledger
        String email = "ghost@opayque.com";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        // Act & Assert: Verify standard security exception propagation
        assertThrows(UsernameNotFoundException.class, () -> userDetailsService.loadUserByUsername(email));
    }
}