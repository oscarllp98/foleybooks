package com.foleybooks.auth.user.api;

/**
 * The resend answer (FR-02): one fixed, generic message returned by every
 * branch — unknown address, already-verified account, throttled account and
 * freshly-issued link alike — so the response can never confirm or deny the
 * existence (or state) of an account (LC-20, NFR-01). Unlike registration
 * this envelope does not even echo the submitted email: with nothing else in
 * the body, every answer is byte-identical by construction. The wording also
 * carries the LC-04 "wait a minute" guidance, which must ride the constant
 * message because a throttle-specific reply would be an existence oracle.
 * No token value ever crosses an HTTP boundary (C24).
 */
public record ResendResponse(String message) {

    static final String GENERIC_MESSAGE =
            "If this address has a pending confirmation, a new link is on its way. "
                    + "If you do not see it, please try again in a minute.";

    /** The single enumeration-safe envelope every resend branch returns. */
    public static ResendResponse generic() {
        return new ResendResponse(GENERIC_MESSAGE);
    }
}
