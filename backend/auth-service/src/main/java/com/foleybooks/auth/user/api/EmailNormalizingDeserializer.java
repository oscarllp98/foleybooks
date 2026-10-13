package com.foleybooks.auth.user.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.util.Locale;

/**
 * Boundary normalization of emailed addresses (FR-01, LC-15, LC-26): trims and
 * lowercases the value while it is still just a JSON token, so Bean Validation
 * ({@code @Email}, {@code @Size}) judges the canonical form rather than the
 * raw padding — a whitespace-padded but otherwise valid address must register
 * as the same account, not fail with 400. Nulls pass through untouched so
 * {@code @NotBlank} stays the single source of the blank-input error. Plus
 * addresses survive the normalization as-is (LC-25). The service layer
 * re-normalizes defensively; this deserializer makes the boundary and the
 * service agree on one canonical value (C23, NFR-07).
 */
public class EmailNormalizingDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String raw = parser.getValueAsString();
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }
}
