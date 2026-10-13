package com.foleybooks.auth.user.service;

import com.foleybooks.auth.token.service.ConfirmationTokenService;
import com.foleybooks.auth.user.api.ConfirmRequest;
import com.foleybooks.auth.user.api.ConfirmResponse;
import com.foleybooks.auth.user.api.RegisterRequest;
import com.foleybooks.auth.user.api.RegisterResponse;
import com.foleybooks.auth.user.api.ResendRequest;
import com.foleybooks.auth.user.api.ResendResponse;

/**
 * Account lifecycle behind {@code /api/v1/auth} (FR-01..FR-03): the business
 * logic the thin controller delegates to. All mutations run transactionally;
 * raw tokens and passwords never reach logs or responses (C24, NFR-01).
 */
public interface UserService {

    /**
     * Registers an account (FR-01): normalizes the email (trim + lowercase,
     * LC-15/LC-26), stores a BCrypt-12 hash of the verbatim password and
     * creates the account in {@code UNVERIFIED} state, then dispatches the
     * confirmation email asynchronously (LC-18). Known emails are handled per
     * the FR-01 duplicate branches — VERIFIED → "already registered" notice
     * to the owner, UNVERIFIED → fresh throttled link (D-04) — and a lost
     * unique-constraint race joins that path (LC-19). Every branch answers
     * with the identical enumeration-safe {@link RegisterResponse} (LC-01).
     */
    RegisterResponse register(RegisterRequest request);

    /**
     * Confirms an email address (FR-02): the raw link token resolves to its
     * stored row through {@link ConfirmationTokenService}, an already-VERIFIED
     * account answers the idempotent success even when the link has since
     * expired (LC-03, LC-23), an unknown or superseded token and a live but
     * past-24-hour one answer 410 with a resend hint (LC-02), and only a live
     * link on an UNVERIFIED account flips the status — through the single
     * atomic UPDATE so simultaneous double-use verifies exactly once and the
     * loser still sees success (LC-23). The spent row survives (ADR-002); the
     * raw token is never logged (C24).
     */
    ConfirmResponse confirm(ConfirmRequest request);

    /**
     * Resends the confirmation email (FR-02, AU-14): the normalized address
     * is looked up and, only when it belongs to an UNVERIFIED account
     * outside the 60-second D-04 throttle window, its active link is rotated
     * (invalidating every previously issued one) and the fresh confirmation
     * mail dispatched asynchronously. Unknown, already-VERIFIED and
     * throttled accounts all quietly skip the write — and every branch
     * answers the identical {@link ResendResponse} 202 so the endpoint can
     * never be used to enumerate accounts (LC-04, LC-20, NFR-01). The raw
     * token never reaches the response or the logs (C24).
     */
    ResendResponse resend(ResendRequest request);
}
