package com.foleybooks.auth.user.api;

import com.foleybooks.auth.user.domain.UserStatus;

/**
 * The registration answer, byte-identical for every FR-01 branch (new,
 * verified-duplicate, unverified-duplicate — LC-01, LC-19): the normalized
 * email, a constant {@code UNVERIFIED} status and the check-your-inbox
 * message. Nothing here ever reveals whether the address exists, and no
 * token or secret crosses an HTTP boundary (NFR-01, C24). The fixed status
 * is deliberate — the account state a duplicate registration met may differ,
 * and reporting it would be the leak FR-01 exists to prevent.
 */
public record RegisterResponse(String email, UserStatus status, String message) {

    static final String CONFIRMATION_MESSAGE =
            "Confirmation is on its way. Check your inbox (and spam folder).";

    /** The single enumeration-safe envelope every register branch returns. */
    public static RegisterResponse unverified(String normalizedEmail) {
        return new RegisterResponse(normalizedEmail, UserStatus.UNVERIFIED, CONFIRMATION_MESSAGE);
    }
}
