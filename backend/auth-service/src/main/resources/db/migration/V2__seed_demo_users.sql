-- AU-05 / FR-14: demo accounts with pre-computed BCrypt-12 hashes (AGENTS.md §7).
-- These are intentionally public portfolio credentials, documented in README.
-- Fixed UUIDs so integration tests can reference them (ADR-002).

INSERT INTO users (id, email, password_hash, role, status)
VALUES ('00000000-0000-0000-0000-000000000001',
        'admin@foleybooks.com',
        '$2a$12$DZQNvc8bNxyZkgsM9C7p..DK2.JatrZ9NPq1JSf8CkwM5EXcsQ4Gi',
        'ADMIN', 'VERIFIED'),
       ('00000000-0000-0000-0000-000000000002',
        'customer@foleybooks.com',
        '$2a$12$C.eZLnTC1Km4PvXRY7tcIueI00LECvMHz2CvxbiV7arnLB08Er1j6',
        'CUSTOMER', 'VERIFIED');
