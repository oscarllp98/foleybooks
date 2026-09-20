package com.foleybooks.auth.user.service;

import com.foleybooks.auth.user.api.RegisterRequest;
import com.foleybooks.auth.user.api.RegisterResponse;

/**
 * Account lifecycle behind {@code /api/v1/auth} (FR-01..FR-03): the business
 * logic the thin controller delegates to. All mutations run transactionally;
 * raw tokens and passwords never reach logs or responses (C24, NFR-01).
 */
public interface UserService {

    /**
     * Registers an account (FR-01): normalizes the email (trim + lowercase,
     * LC-15/LC-26), stores a BCrypt-12 hash of the verbatim password and
     * creates the account in {@code UNVERIFIED} state, then dispatches the
     * confirmation email asynchronously (LC-18). Known emails are handled per
     * the FR-01 duplicate branches — VERIFIED → "already registered" notice
     * to the owner, UNVERIFIED → fresh throttled link (D-04) — and a lost
     * unique-constraint race joins that path (LC-19). Every branch answers
     * with the identical enumeration-safe {@link RegisterResponse} (LC-01).
     */
    RegisterResponse register(RegisterRequest request);
}
