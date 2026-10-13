package com.foleybooks.auth.token.service;

import com.foleybooks.auth.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The single 401 answer of {@code POST /api/v1/auth/refresh} (FR-04,
 * LC-08): an unknown value, an expired session, a spent (ROTATED) token —
 * reuse detected or rotation race lost — or a REVOKED one. Every branch
 * renders identically so the response never reveals which state the session
 * row is in, and it tells the client the one recovery that exists: log in
 * again. Raw token values never enter the message (C24, NFR-01).
 */
public final class RefreshTokenException extends ApiException {

    private RefreshTokenException() {
        super(HttpStatus.UNAUTHORIZED,
                "invalid-or-expired-refresh-token",
                "Session is no longer valid",
                "This session is no longer valid. Please log in again.");
    }

    /** The presented refresh token resolved to nothing usable (FR-04, LC-08, LC-22). */
    public static RefreshTokenException invalidOrExpired() {
        return new RefreshTokenException();
    }
}
