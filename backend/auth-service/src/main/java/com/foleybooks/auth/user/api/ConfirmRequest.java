package com.foleybooks.auth.user.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Confirmation payload (FR-02): the raw single-use token the
 * {@code /verify-email?token=…} link carries (D-01). Only its SHA-256 digest
 * ever left the service, so validation stays structural — presence plus a
 * length ceiling, which every issued link already discloses — and nothing
 * here checks the hex format: a too-short, tampered or rotated-away value
 * passes validation and is answered uniformly by the 410 invalid-or-expired
 * branch. The value is a credential: never logged, echoed or stored raw
 * (C24, NFR-01).
 */
public record ConfirmRequest(

        @NotBlank(message = "must not be blank")
        @Size(max = 64, message = "must be at most 64 characters")
        String token) {
}
