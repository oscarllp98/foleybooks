package com.foleybooks.auth.user.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registration payload (FR-01). The email arrives already normalized to its
 * canonical form — trim + lowercase happens at deserialization
 * ({@link EmailNormalizingDeserializer}) so the format/length constraints
 * below judge the stored value, not the raw padding (LC-15, LC-26);
 * plus-addressing stays significant — {@code user+tag@domain} is its own
 * account (LC-25). The password is kept verbatim, whitespace included
 * (LC-26): min 8 chars with at least one letter and one digit, and at most
 * the 72 bytes the BCrypt codec can ingest — beyond that registration would
 * fail inside the encoder instead of answering 400 here. Records never
 * expose entities (C9); Bean Validation runs at the boundary before anything
 * reaches the service (C23).
 */
public record RegisterRequest(

        @JsonDeserialize(using = EmailNormalizingDeserializer.class)
        @NotBlank(message = "must not be blank")
        @Email(message = "must be a well-formed email address")
        @Size(max = 320, message = "must be at most 320 characters")
        String email,

        @NotBlank(message = "must not be blank")
        @Size(min = 8, message = "must be at least 8 characters")
        @MaxUtf8Bytes(72)
        @Pattern(regexp = "(?s)^(?=.*\\p{L})(?=.*\\d).*$",
                message = "must contain at least one letter and one digit")
        String password) {
}
