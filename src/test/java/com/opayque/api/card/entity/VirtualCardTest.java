package com.opayque.api.card.entity;

import com.opayque.api.infrastructure.encryption.AttributeEncryptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit Test for VirtualCard Entity Logic.
 * Uses Reflection to verify private Lifecycle Hooks (@PrePersist/@PreUpdate).
 */
class VirtualCardTest {

    @Test
    @DisplayName("Lifecycle: Generates Fingerprint when PAN is present (True Branch)")
    void synchronizeFingerprint_WhenPanPresent() throws Exception {
        // Given
        String rawPan = "4111222233334444";
        String expectedHash = "hmac-sha256-hash";
        VirtualCard card = VirtualCard.builder().pan(rawPan).build();

        // When (Mocking Static Blind Index to avoid loading the real Engine)
        try (MockedStatic<AttributeEncryptor> mockedCrypto = mockStatic(AttributeEncryptor.class)) {
            mockedCrypto.when(() -> AttributeEncryptor.blindIndex(rawPan)).thenReturn(expectedHash);

            // Access private method via Reflection
            Method method = VirtualCard.class.getDeclaredMethod("synchronizeFingerprint");
            method.setAccessible(true);
            method.invoke(card);
        }

        // Then
        assertThat(card.getPanFingerprint()).isEqualTo(expectedHash);
    }

    @Test
    @DisplayName("Lifecycle: Skips Fingerprint when PAN is Null (False Branch)")
    void synchronizeFingerprint_WhenPanNull() throws Exception {
        // Given
        VirtualCard card = VirtualCard.builder().pan(null).build();

        // When
        try (MockedStatic<AttributeEncryptor> mockedCrypto = mockStatic(AttributeEncryptor.class)) {

            // Access private method via Reflection
            Method method = VirtualCard.class.getDeclaredMethod("synchronizeFingerprint");
            method.setAccessible(true);
            method.invoke(card);

            // Then
            // Verify blindIndex was NEVER called (proving we hit the 'else' logic)
            mockedCrypto.verify(() -> AttributeEncryptor.blindIndex(anyString()), never());
            assertThat(card.getPanFingerprint()).isNull();
        }
    }
}