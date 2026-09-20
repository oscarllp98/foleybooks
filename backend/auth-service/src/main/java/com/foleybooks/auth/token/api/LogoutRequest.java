package com.foleybooks.auth.token.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Logout payload (FR-05, AU-18): the raw refresh token whose session should
 * end. Like {@link RefreshRequest}, validation is structural only — presence
 * plus the 64-hex-char length every issued value already discloses — because
 * a too-long garbage value is a malformed request (400, C23) while any
 * well-formed one, known or not, active or already spent, must land on the
 * same idempotent 204 (LC-09). The value is a credential: never logged,
 * echoed back or stored raw (C24, NFR-01). No access token is required — the
 * refresh token in the body is the only credential this call carries (D-05,
 * LC-21).
 */
public record LogoutRequest(

        @NotBlank(message = "must not be blank")
        @Size(max = 64, message = "must be at most 64 characters")
        String refreshToken) {
}
