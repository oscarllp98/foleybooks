package com.foleybooks.auth.user.domain;

/**
 * Account roles carried in the JWT {@code roles} claim (AGENTS.md §5).
 * ADMIN exists for FR-14 seeding; the MVP grants it no capability (spec §FR-14).
 * Persisted as {@code VARCHAR(20)} + CHECK per ADR-002 — never a native PG enum.
 */
public enum UserRole {
    CUSTOMER,
    ADMIN
}
