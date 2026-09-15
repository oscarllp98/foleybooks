package com.foleybooks.auth.user.domain;

/**
 * Email-verification state machine (FR-01, FR-02): UNVERIFIED at registration,
 * VERIFIED after confirmation. Login of an UNVERIFIED account is rejected 403
 * (LC-05). Persisted as {@code VARCHAR(20)} + CHECK per ADR-002.
 */
public enum UserStatus {
    UNVERIFIED,
    VERIFIED
}
