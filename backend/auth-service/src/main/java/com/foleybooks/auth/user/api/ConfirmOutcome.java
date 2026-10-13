package com.foleybooks.auth.user.api;

/**
 * Which 200 a confirmation produced (FR-02). Both outcomes leave the account
 * VERIFIED and both are successes — LC-03/LC-23 demand that re-opening a
 * spent link never degrades into an error — but the UI copy differs:
 * a freshly verified address invites the user to log in, while a spent link
 * tells them verification already happened. The discriminator lets
 * {@code VerifyEmail} branch without parsing prose.
 */
public enum ConfirmOutcome {

    /** This request flipped the account from UNVERIFIED to VERIFIED. */
    CONFIRMED,

    /** The account was already VERIFIED; the call is idempotent (LC-03, LC-23). */
    ALREADY_CONFIRMED
}
