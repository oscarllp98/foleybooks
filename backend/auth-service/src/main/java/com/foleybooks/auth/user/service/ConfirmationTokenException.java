package com.foleybooks.auth.user.service;

import com.foleybooks.auth.common.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The two 410 answers of email confirmation (FR-02, LC-02): a token that
 * resolves to no stored row — unknown, superseded by a resend, or already
 * cleaned up — and a row whose 24-hour window has passed. Both render as
 * ProblemDetail through {@code GlobalExceptionHandler} and both carry the
 * same {@code resendHint} property so a client learns how to recover without
 * the response distinguishing a never-issued link from an expired one. Raw
 * token values never enter the message (C24).
 */
public final class ConfirmationTokenException extends ApiException {

    static final String RESEND_HINT = "Request a new link with POST /api/v1/auth/resend.";

    private static final Map<String, Object> RESEND_HINT_PROPERTY = Map.of("resendHint", RESEND_HINT);

    private ConfirmationTokenException(String type, String title, String detail) {
        super(HttpStatus.GONE, type, title, detail, RESEND_HINT_PROPERTY);
    }

    /** The presented token matches no stored row: unknown, or invalidated by a resend. */
    public static ConfirmationTokenException invalidOrExpired() {
        return new ConfirmationTokenException(
                "invalid-or-expired-confirmation-token",
                "Confirmation link is invalid or expired",
                "This confirmation link is not valid or has expired. You can request a new one.");
    }

    /** The row exists but its 24-hour validity window has passed (LC-02). */
    public static ConfirmationTokenException expired() {
        return new ConfirmationTokenException(
                "expired-confirmation-token",
                "Confirmation link has expired",
                "This confirmation link has expired. You can request a new one.");
    }
}
