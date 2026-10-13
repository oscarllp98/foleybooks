package com.foleybooks.auth.token.service;

import com.foleybooks.auth.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The single generic 401 answer of {@code POST /api/v1/auth/login} (FR-03,
 * LC-06): unknown address, wrong password — and, for the client, the two are
 * the same failure. The body never says which field was wrong or whether the
 * account exists (NFR-01); repeated attempts stay identical (no lockout in
 * the MVP). The only post-authentication distinction is the 403
 * {@link EmailNotVerifiedException}, which by definition requires a correct
 * password and therefore leaks nothing to a non-owner.
 */
public final class InvalidCredentialsException extends ApiException {

    private InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED,
                "invalid-credentials",
                "Invalid email or password",
                "The email or password is incorrect.");
    }

    /** The presented email/password pair matched no verified-credential rule (FR-03, LC-06). */
    public static InvalidCredentialsException generic() {
        return new InvalidCredentialsException();
    }
}
