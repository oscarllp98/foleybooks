package com.foleybooks.auth.token.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Refresh payload (FR-04, AU-16): the raw single-use refresh token the client
 * holds from login or the previous rotation. Like {@link com.foleybooks.auth.user.api.ConfirmRequest},
 * validation is structural only — presence plus the 64-hex-char length every
 * issued value already discloses — because a too-short, tampered, spent or
 * revoked token must hit the same generic 401 as an unknown one, never a
 * branch-specific answer (LC-08, LC-22). The value is a credential: never
 * logged, echoed back or stored raw (C24, NFR-01).
 */
public record RefreshRequest(

        @NotBlank(message = "must not be blank")
        @Size(max = 64, message = "must be at most 64 characters")
        String refreshToken) {
}
