package com.foleybooks.auth.token.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.foleybooks.auth.user.api.EmailNormalizingDeserializer;
import com.foleybooks.auth.user.api.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login payload (FR-03, AU-17). The email carries the same boundary
 * normalization as {@link com.foleybooks.auth.user.api.RegisterRequest} —
 * trim + lowercase via {@link EmailNormalizingDeserializer} so mixed-case or
 * whitespace-padded addresses resolve to the same account (LC-24, LC-26) —
 * and the service layer re-normalizes defensively.
 *
 * <p>Validation is deliberately <em>structural only</em>: presence plus the
 * 72-byte BCrypt ingest cap. The registration complexity rules (min length,
 * letter + digit) must NOT be re-applied here — a five-character wrong
 * password is a wrong password, and FR-03 promises it the single generic 401,
 * not a field-explaining 400 that would hand attackers a shape oracle
 * (LC-06). The byte cap only protects against the encoder's hard limit, and
 * it fires identically for every address because validation runs before any
 * lookup (NFR-01). The password is matched verbatim, whitespace included
 * (LC-26); neither field is ever logged or echoed (C24).
 */
public record LoginRequest(

        @JsonDeserialize(using = EmailNormalizingDeserializer.class)
        @NotBlank(message = "must not be blank")
        @Email(message = "must be a well-formed email address")
        @Size(max = 320, message = "must be at most 320 characters")
        String email,

        @NotBlank(message = "must not be blank")
        @MaxUtf8Bytes(72)
        String password) {
}
