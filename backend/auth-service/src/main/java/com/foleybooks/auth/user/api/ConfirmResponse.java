package com.foleybooks.auth.user.api;

/**
 * The confirmation answer (FR-02): the {@link ConfirmOutcome} and the
 * user-facing message. The account email is deliberately not echoed —
 * a leaked link would otherwise disclose the address beyond what the holder
 * already controls (NFR-01), and nothing in the flow needs it. No token value
 * ever crosses an HTTP boundary in either direction (C24).
 */
public record ConfirmResponse(ConfirmOutcome outcome, String message) {

    static final String CONFIRMED_MESSAGE = "Email confirmed. You can log in now.";
    static final String ALREADY_CONFIRMED_MESSAGE = "This email was already confirmed. You can log in now.";

    /** The link was live and this request performed the verification. */
    public static ConfirmResponse confirmed() {
        return new ConfirmResponse(ConfirmOutcome.CONFIRMED, CONFIRMED_MESSAGE);
    }

    /** Re-opening a spent link: success without a second write (LC-03, LC-23). */
    public static ConfirmResponse alreadyConfirmed() {
        return new ConfirmResponse(ConfirmOutcome.ALREADY_CONFIRMED, ALREADY_CONFIRMED_MESSAGE);
    }
}
