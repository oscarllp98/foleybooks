package com.foleybooks.auth.user.api;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

/**
 * Counts the UTF-8 encoded byte length of the value — the exact measure
 * {@code org.springframework.security.crypto.bcrypt.BCrypt#hashpw} rejects
 * above 72 bytes — so the boundary and the codec agree.
 */
public class MaxUtf8BytesValidator implements ConstraintValidator<MaxUtf8Bytes, String> {

    private int maxBytes;

    @Override
    public void initialize(MaxUtf8Bytes constraintAnnotation) {
        this.maxBytes = constraintAnnotation.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }
}
