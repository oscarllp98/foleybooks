package com.foleybooks.auth.user.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Resend payload (FR-02): the account email the unverified visitor submits
 * because they cannot log in (FR-02 mandates an unauthenticated request).
 * The same boundary normalization as {@link RegisterRequest} applies — trim +
 * lowercase via {@link EmailNormalizingDeserializer} so the lookup hits the
 * canonical stored address (LC-15, LC-26) and the constraints below judge
 * that canonical form, not the raw padding. Nothing else belongs here: the
 * response must never depend on anything beyond the address, and no token
 * or credential crosses this payload in either direction (C24, NFR-01).
 */
public record ResendRequest(

        @JsonDeserialize(using = EmailNormalizingDeserializer.class)
        @NotBlank(message = "must not be blank")
        @Email(message = "must be a well-formed email address")
        @Size(max = 320, message = "must be at most 320 characters")
        String email) {
}
