package com.foleybooks.auth.user.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the UTF-8 byte capacity constraint (plan §6.1 layer 1): the
 * count that matters is the encoded byte length the BCrypt codec sees, not the
 * character count {@code @Size} judges — a multibyte password can satisfy
 * every spec rule (FR-01) and still blow up inside {@code BCrypt.hashpw}.
 */
class MaxUtf8BytesValidatorTest {

    @MaxUtf8Bytes(72)
    @SuppressWarnings("unused")
    private static String probe;

    private final MaxUtf8BytesValidator validator = new MaxUtf8BytesValidator();

    @BeforeEach
    void setUp() throws NoSuchFieldException {
        Field field = MaxUtf8BytesValidatorTest.class.getDeclaredField("probe");
        validator.initialize(field.getAnnotation(MaxUtf8Bytes.class));
    }

    @Test
    void isValid_whenNull_returnsTrue() {
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    void isValid_whenAsciiAtSeventyTwoBytes_returnsTrue() {
        assertThat(validator.isValid("Aa" + "x".repeat(70), null)).isTrue();
    }

    @Test
    void isValid_whenAsciiOverSeventyTwoBytes_returnsFalse() {
        assertThat(validator.isValid("Aa" + "x".repeat(71), null)).isFalse();
    }

    @Test
    void isValid_whenMultibyteWithinCharLimitButOverByteLimit_returnsFalse() {
        // 40 chars pass @Size(max=72)-style checks, but 'é' encodes to 2 bytes.
        String value = "é".repeat(40);
        assertThat(value).hasSize(40);
        assertThat(validator.isValid(value, null)).isFalse();
    }
}
