package com.opayque.api.infrastructure.util;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.opayque.api.infrastructure.serialization.MaskingSerializer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Security Annotation: PII Masking Marker.
///
/// Triggers the MaskingSerializer during the JSON serialization phase.
/// Utilizing @JacksonAnnotationsInside allows this to function as a meta-annotation,
/// abstracting the specific serializer implementation from the DTO layer.
///
/// Application: Use on sensitive String fields in Response DTOs.
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@JacksonAnnotationsInside
@JsonSerialize(using = MaskingSerializer.class)
public @interface Masked {
}