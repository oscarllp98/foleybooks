package com.foleybooks.auth.token.service;

import com.foleybooks.auth.token.api.TokenPair;

/**
 * Rotating refresh-session lifecycle (FR-04, AU-16). Refresh tokens are
 * opaque 256-bit values whose SHA-256 digest is the only thing that reaches
 * the database (ADR-002); each use is single — the spent row flips to ROTATED
 * and stays so reuse remains the theft signal — and the replacement session
 * gets a fresh sliding 7-day window. Session start for login lives with
 * AU-17; logout's row delete with AU-18.
 */
public interface TokenService {

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
}
