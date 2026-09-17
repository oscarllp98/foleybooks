package com.foleybooks.auth.user.api;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Caps a string at a maximum number of UTF-8 bytes (as opposed to
 * {@code @Size}, which counts characters). Registration uses it to enforce the
 * BCrypt codec's hard 72-byte limit at the boundary: Spring Security's
 * {@code BCrypt.hashpw} throws {@link IllegalArgumentException} past that, so
 * an over-long — but otherwise valid — password would otherwise surface as a
 * 500 instead of the 400 the API contract demands (AGENTS.md §6, C23, NFR-06).
 * The limit must never silently truncate the password, which would let two
 * distinct inputs authenticate alike; failing fast at validation keeps that
 * impossible. Nulls are valid (leave presence checks to {@code @NotBlank}).
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
public @interface MaxUtf8Bytes {

    String message() default "must be at most {value} bytes";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Maximum number of UTF-8 bytes the annotated value may encode to. */
    int value();
}
