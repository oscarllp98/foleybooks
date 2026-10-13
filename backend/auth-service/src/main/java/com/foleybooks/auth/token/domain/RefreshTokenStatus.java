package com.foleybooks.auth.token.domain;

/**
 * Refresh-token session lifecycle (FR-04, ADR-002): ACTIVE → ROTATED on
 * rotation (row kept so reuse stays detectable, LC-08) or → REVOKED by the
 * bulk reuse-detection update. Logout deletes the row instead (D-05).
 * Persisted as {@code VARCHAR(20)} + CHECK.
 */
public enum RefreshTokenStatus {
    ACTIVE,
    ROTATED,
    REVOKED
}
