package com.foleybooks.auth.token.service;

import com.foleybooks.auth.token.api.LoginRequest;
import com.foleybooks.auth.token.api.TokenPair;

/**
 * Session lifecycle behind {@code /api/v1/auth} (FR-03..FR-05): login opens a
 * session, refresh rotates one, logout ends one. Refresh tokens are opaque
 * 256-bit values whose SHA-256 digest is the only thing that reaches the
 * database (ADR-002); each use is single — the spent row flips to ROTATED and
 * stays so reuse remains the theft signal — and the replacement session gets
 * a fresh sliding 7-day window.
 */
public interface TokenService {

    /**
     * Opens a new session for a verified credential pair (FR-03, AU-17): the
     * email is re-normalized defensively (trim + lowercase, LC-24/LC-26), the
     * password is matched verbatim against the stored BCrypt-12 hash, and an
     * UNVERIFIED account is sent to confirm its email before any session is
     * minted. The winner gets the same plan §2 envelope refresh rotates into:
     * a 15-minute access JWT plus an ACTIVE 7-day refresh session.
     *
     * @throws InvalidCredentialsException when the address is unknown or the
     *                                    password does not match — one generic 401
     *                                    that never says which (FR-03, LC-06)
     * @throws EmailNotVerifiedException  when the credentials are correct but
     *                                    the account is still UNVERIFIED (LC-05)
     */
    TokenPair login(LoginRequest request);

    /**
     * Exchanges a presented refresh token for a fresh token pair: an ACTIVE,
     * unexpired token rotates to ROTATED, mints the successor session and
     * signs a new access JWT for its owner. An unknown, expired, spent or
     * revoked token answers the single generic 401 — and a spent one first
     * revokes every ACTIVE session of the account (LC-08), the theft signal,
     * which also covers a lost rotation race (LC-22).
     *
     * @throws RefreshTokenException when the value resolves to nothing usable
     */
    TokenPair refresh(String refreshToken);

    /**
     * Ends the session the presented refresh token belongs to (FR-05, AU-18):
     * the ACTIVE row whose SHA-256 digest matches the value is deleted — the
     * current session only, so other devices keep their rows and their
     * refreshes keep working (LC-21). There is no other observable outcome:
     * an unknown, already logged-out, spent or revoked value deletes nothing,
     * and both branches are the same idempotent success (LC-09) with no
     * token-dependent response, log line or failure mode (C24, NFR-01). The
     * {@code ROTATED}/{@code REVOKED} rows stay exactly where they are — they
     * are the reuse evidence LC-08 needs (ADR-002), and logout must never be
     * a way to erase it.
     */
    void logout(String refreshToken);
}
