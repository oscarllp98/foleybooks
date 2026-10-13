-- AU-05 / ADR-002: auth_db schema — users, refresh_tokens, confirmation_tokens.
-- Enum-like columns are VARCHAR(20) + CHECK (not native PG enums) so Hibernate
-- string-enum mapping passes ddl-auto=validate (C17).

CREATE TABLE users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(320) NOT NULL,
    password_hash VARCHAR(60)  NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_users_email       UNIQUE (email),
    CONSTRAINT ck_users_email_lower CHECK (email = lower(email)),
    CONSTRAINT ck_users_role        CHECK (role   IN ('CUSTOMER', 'ADMIN')),
    CONSTRAINT ck_users_status      CHECK (status IN ('UNVERIFIED', 'VERIFIED'))
);

-- Rotated rows are kept (status ROTATED) so token reuse stays detectable (LC-08);
-- only the SHA-256 hex digest of the opaque token is stored (C24).
CREATE TABLE refresh_tokens (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    status     VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_refresh_tokens_user   FOREIGN KEY (user_id) REFERENCES users (id)
                                        ON DELETE CASCADE,
    CONSTRAINT uk_refresh_tokens_hash   UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_status CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED'))
);

-- Supports the revoke-all bulk update and per-user session listings (ADR-002).
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);

-- At most one active confirmation link per account, enforced in the DB (FR-02):
-- a resend replaces this row's token_hash/expires_at. The row survives
-- verification so a spent link stays resolvable (LC-03, ADR-002).
CREATE TABLE confirmation_tokens (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID        NOT NULL,
    token_hash     VARCHAR(64) NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    last_resend_at TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_confirmation_tokens_user FOREIGN KEY (user_id) REFERENCES users (id)
                                           ON DELETE CASCADE,
    CONSTRAINT uk_confirmation_tokens_user UNIQUE (user_id),
    CONSTRAINT uk_confirmation_tokens_hash UNIQUE (token_hash)
);
