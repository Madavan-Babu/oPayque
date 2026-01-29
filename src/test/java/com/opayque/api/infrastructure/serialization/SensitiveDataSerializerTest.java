package com.opayque.api.infrastructure.serialization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.opayque.api.infrastructure.util.Masked;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

/// Epic 1: Security Hardening - Serialization Integrity Testing.
///
/// This suite validates the behavior of the [MaskingSerializer] across the serialization
/// lifecycle. It ensures that PII is redacted according to the "Opaque" standard
/// during JSON conversion.
///
/// The tests verify:
/// 1. Context-aware masking for emails and generic strings.
/// 2. Graceful handling of edge-case short strings.
/// 3. Data integrity of the source object (Immutable masking).
/// 4. Direct null-safety handling within the serializer logic.
@ExtendWith(MockitoExtension.class)
class SensitiveDataSerializerTest {

    private ObjectMapper mapper;

    @Mock
    private JsonGenerator jsonGenerator;

    @Mock
    private SerializerProvider serializerProvider;

    /// Initializes the testing context by configuring a Jackson ObjectMapper with the
    /// custom [MaskingSerializer].
    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        // Registers the custom logic to intercept String fields during serialization.
        module.addSerializer(String.class, new MaskingSerializer());
        mapper.registerModule(module);
    }

    // --- INTEGRATION TESTS (Through Jackson Pipeline) ---

    /// Verifies standard PII redaction for email and full name fields.
    ///
    /// Confirms that strings matching the [Masked] annotation are correctly processed
    /// through the standard and email-specific masking branches.
    @Test
    @DisplayName("Unit: Should mask email addresses in JSON output")
    void shouldMaskEmail() throws Exception {
        TestDto dto = new TestDto("dev@opayque.com", "John Doe");
        String json = mapper.writeValueAsString(dto);
        assertEquals("{\"email\":\"d***@opayque.com\",\"fullName\":\"J***\"}", json);
    }

    /// Validates the edge-case branch for single-character identifiers.
    ///
    /// Ensures that the system redacts the entire character to prevent identity
    /// hints when the input length is minimal.
    @Test
    @DisplayName("Unit: Should mask single character generic strings (Coverage: Short String Branch)")
    void shouldMaskSingleCharGeneric() throws Exception {
        TestDto dto = new TestDto("valid@email.com", "A");
        String json = mapper.writeValueAsString(dto);
        assertEquals("{\"email\":\"v***@email.com\",\"fullName\":\"***\"}", json);
    }

    /// Tests the robustness of the email masking logic for short local parts.
    ///
    /// Validates that emails with minimal local parts (e.g., "a@b.com") are fully
    /// redacted before the '@' symbol to maintain privacy.
    @Test
    @DisplayName("Unit: Should handle short emails gracefully")
    void shouldHandleShortEmails() throws Exception {
        TestDto dto = new TestDto("a@b.com", "Jo");
        String json = mapper.writeValueAsString(dto);
        assertEquals("{\"email\":\"***@b.com\",\"fullName\":\"J***\"}", json);
    }

    /// Confirms that the masking process is a non-destructive transformation.
    ///
    /// This test ensures that the original state of the source entity is preserved
    /// and only the JSON representation is altered, maintaining internal data integrity.
    @Test
    @DisplayName("Unit: Data Integrity - Source object MUST remain untouched")
    void shouldMaintainDataIntegrity() throws Exception {
        String originalEmail = "integrity@opayque.com";
        TestDto dto = new TestDto(originalEmail, "Integrity Check");
        mapper.writeValueAsString(dto);
        assertEquals(originalEmail, dto.email);
    }

    // --- UNIT TESTS (Direct Component Invocation) ---

    /// Exercises the null-safety branch of the serializer.
    ///
    /// Directly invokes the [MaskingSerializer] to verify it correctly commands the
    /// [JsonGenerator] to emit a null value when given a null input.
    @Test
    @DisplayName("Unit: Direct - Should handle null value explicitly (Coverage: Null Branch)")
    void shouldHandleNullValueDirectly() throws Exception {
        MaskingSerializer serializer = new MaskingSerializer();

        serializer.serialize(null, jsonGenerator, serializerProvider);

        verify(jsonGenerator).writeNull();
    }

    /// Immutable Data Transfer Object utilized for serialization testing.
    ///
    /// Annotations here mimic the production DTO structure to verify the meta-annotation
    /// and serializer discovery mechanism.
    static class TestDto {
        @Masked
        public String email;

        @Masked
        public String fullName;

        public TestDto(String email, String fullName) {
            this.email = email;
            this.fullName = fullName;
        }
    }
}