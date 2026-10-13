package com.foleybooks.auth.token.service;

import com.foleybooks.auth.common.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The 403 answer of {@code POST /api/v1/auth/login} for an account whose
 * email is still UNVERIFIED (FR-03, LC-05). Deliberately not a generic 401:
 * the "confirm your email" message is exactly what a legitimate user needs,
 * and the branch is only reachable <em>after</em> the password has been
 * verified — an attacker without the credentials still sees the indistinct
 * {@link InvalidCredentialsException}, so nothing about account existence
 * leaks to them (NFR-01). The recovery hint rides the same {@code resendHint}
 * property established for FR-02's 410s (plan §2) while {@code detail} stays
 * user-facing.
 */
public final class EmailNotVerifiedException extends ApiException {

    /**
     * Identical wording to FR-02's {@code ConfirmationTokenException} hint —
     * intentionally duplicated rather than cross-wired: the string is part of
     * the response contract, not shared business logic, and the two features
     * stay independently testable (same pattern as each service's own email
     * normalization).
     */
    private static final String RESEND_HINT = "Request a new link with POST /api/v1/auth/resend.";

    private static final Map<String, Object> RESEND_HINT_PROPERTY = Map.of("resendHint", RESEND_HINT);

    private EmailNotVerifiedException() {
        super(HttpStatus.FORBIDDEN,
                "email-not-verified",
                "Email not verified",
                "Your email address is not confirmed yet. Check your inbox for the confirmation "
                        + "link, or request a new one.",
                RESEND_HINT_PROPERTY);
    }

    /** Credentials verified but the account status is UNVERIFIED (LC-05). */
    public static EmailNotVerifiedException pendingConfirmation() {
        return new EmailNotVerifiedException();
    }
}
