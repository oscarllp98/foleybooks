package com.foleybooks.auth.token.service;

import com.foleybooks.auth.token.domain.ConfirmationToken;
import com.foleybooks.auth.user.domain.User;
import java.util.Optional;

/**
 * Owns the email-confirmation link lifecycle: opaque 256-bit values whose
 * SHA-256 hex digest is the only thing that reaches the database, a 24-hour
 * TTL, at most one active link per account (a re-issue replaces the stored
 * hash, invalidating every earlier link), and the {@code last_resend_at}
 * stamp behind the 60-second resend throttle. The raw value exists once —
 * in the returned String, destined for the confirmation email — and is
 * never persisted or logged.
 */
public interface ConfirmationTokenService {

    /**
     * Issues the first confirmation link for an account, at registration.
     * Leaves {@code last_resend_at} null — the throttle clock starts at the
     * first resend, not at issuance. If a row somehow already exists its
     * link is replaced, preserving the one-row-per-user invariant.
     *
     * @return the raw token value to embed in the {@code /verify-email?token=}
     *         link; never persist or log it
     */
    String issue(User user);

    /**
     * Replaces the account's active link with a fresh token and a fresh
     * 24-hour window, and stamps {@code last_resend_at} — used by resend
     * and by duplicate registration against an unverified account. Every
     * previously issued link stops resolving.
     *
     * @return the new raw token value for the outgoing email
     */
    String rotate(User user);

    /**
     * Resolves a presented raw token to its row by re-computing the SHA-256
     * digest identically to issuance. The row survives verification, so a
     * hit alone does not mean "verifiable" — the confirmation flow
     * discriminates the already-confirmed and expired branches from the
     * row's and the account's state.
     */
    Optional<ConfirmationToken> find(String rawToken);

    /**
     * True while the account is inside the 60-second resend throttle
     * window: its stored row carries a {@code last_resend_at} less than a
     * minute ago. No row, or a never-resent row, is never throttled.
     */
    boolean isResendThrottled(User user);
}
