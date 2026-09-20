# ADR-002: auth_db schema (users, refresh_tokens, confirmation_tokens)

## Status

Accepted — implements the Spec 001 plan §3 sketch
for auth_db; prerequisite docs gate for AU-05 (Flyway) and AU-06 (entities/repositories).
Refines AGENTS.md §5 and supersedes the plan §3 note about deleting confirmation tokens on
verification (see *Confirmation tokens* and *Deviations* below).

## Context

auth-service owns `auth_db` exclusively (C18: no other service may reference it) and must
persist exactly three things to satisfy FR-01..FR-05:

1. **Accounts** — email, BCrypt-12 password hash, role, verification state (FR-01, FR-03).
2. **Refresh tokens** — opaque 256-bit values, hashed at rest, rotated with a sliding
   7-day window, with reuse detection that revokes every session of the account
   (FR-04, LC-08; AGENTS.md §5).
3. **Email-confirmation tokens** — single-use, 24-hour links, at most one active link per
   account, with a resend timestamp backing the 60 s throttle (FR-02, D-03, D-04).

Access tokens are **not** stored: they are stateless RS256 JWTs validated by signature and
`exp`, so no deny-list table exists — logout revokes the refresh credential only, and the
15-minute access-token window is the accepted residual risk (FR-05, D-05).

The schema must survive `ddl-auto=validate` (C17) with plain `@Enumerated(EnumType.STRING)`
entities, be created by Flyway `V1` and seeded by `V2` (C16), and never hold a plaintext
credential (C24, NFR-01).

## Decision

Three tables, one schema (`public`), PostgreSQL 16. Flyway owns every column; entities never
generate DDL. Enum-like columns are `VARCHAR(20)` + `CHECK` (not native Postgres enum
types) so Hibernate string-enum mapping validates cleanly and future values need only a
new migration replacing the check constraint.

### users

```sql
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
```

- `email` is stored normalized (trim + lowercase, LC-15/LC-24/LC-25). Normalization is
  performed in the service layer; `ck_users_email_lower` is the at-rest backstop. Plus
  addressing is **not** collapsed — the uniqueness check is literal after normalization.
  `VARCHAR(320)` is generous headroom over the RFC 5321 254-char limit. `uk_users_email`
  is what makes the concurrent-registration race (LC-19) safe: exactly one INSERT wins,
  the loser re-reads.
- `password_hash` holds the BCrypt (strength 12) digest — 60 chars exactly; the plaintext
  password is never persisted or logged (NFR-01).
- `role` / `status` map to `UserRole` and `UserStatus` Java enums. `ADMIN` exists from
  day one because FR-14 seeds it, even though the MVP grants it no capability.

### refresh_tokens

```sql
CREATE TABLE refresh_tokens (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    status     VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_refresh_tokens_user  FOREIGN KEY (user_id) REFERENCES users (id)
                                       ON DELETE CASCADE,
    CONSTRAINT uk_refresh_tokens_hash  UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_status CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED'))
);

CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
```

- Only the **SHA-256 hex digest** (64 lowercase hex chars) of the opaque token is stored;
  `uk_refresh_tokens_hash` doubles as the refresh/logout lookup path. The raw value
  exists once, in the HTTP response, and is never persisted (C24).
- Token hashes are `VARCHAR(64)`, not `CHAR(64)`: Postgres types a `CHAR` column as
  `bpchar`, which Hibernate's `validate` step can report as a mismatch against the
  `String` entity field — and blank-padded comparison buys nothing for fixed-length hex.
- Rows are **never deleted on rotation**: the predecessor flips to `ROTATED` and stays, so
  presenting a spent token is detectable and triggers `revokeAllForUser` (LC-08). A
  delete-on-rotate design would silently turn theft into a plain 401.
- **Logout deletes the current session's row** (D-05, AU-18, `deleteActiveByTokenHash`); it
  does not flip a status. The delete is guarded on `status = ACTIVE`: spent `ROTATED` and
  theft-`REVOKED` rows are never removed, so logging out with an old value cannot erase the
  reuse evidence the clause above protects — an unguarded delete would let a stale-token
  logout silently downgrade LC-08 detection to a plain 401. Matching zero rows (unknown,
  already logged out, spent) is the idempotent success (FR-05, LC-09). Other sessions keep
  their own rows and are unaffected (LC-21).
  `REVOKED` is therefore written only by the bulk reuse-detection update
  (`UPDATE … SET status='REVOKED', updated_at=now() WHERE user_id = ? AND
  status='ACTIVE'`); a logged-out token simply misses the lookup.
- `expires_at` carries the sliding 7-day value minted at issue time (D-03).
- `ix_refresh_tokens_user` supports the revoke-all bulk update and per-user session
  listings; it is not a lookup path for the token itself.

### confirmation_tokens

```sql
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
```

- `uk_confirmation_tokens_user` enforces "at most one active link per account" (FR-02)
  **in the database** rather than by application convention: a resend **replaces** the
  row's `token_hash`/`expires_at`, which invalidates every link issued earlier.
- `last_resend_at` is `NULL` until the first resend and is the single source of truth for
  the 60 s throttle (D-04); duplicate registration re-confirmation reads the same column.
- The row **survives verification**. The plan §3 sketch originally proposed deleting it,
  but that cannot satisfy
  FR-02/LC-03: once the hash lookup returns nothing, the service has no way to tell an
  already-confirmed re-open from an unknown token, and the idempotent "already confirmed"
  response degrades to 410. Retaining the row keeps the spent link resolvable, and
  `users.status` is what discriminates the two cases.
- Verification is a single atomic statement on the parent row, relying on the Postgres
  row lock for LC-23:
  `UPDATE users SET status='VERIFIED', updated_at=now() WHERE id = ? AND status='UNVERIFIED'`
  (the service binds the timestamp explicitly — same value it used for the expiry check).
  The second concurrent request observes 0 updated rows and the `VERIFIED` status, and
  returns 200 "already confirmed" rather than re-verifying. No trigger, no extra column,
  no second write.
- `expires_at` implements the 24-hour link validity (FR-02, LC-02).

### Cross-cutting rules

- **Audit columns everywhere**: `created_at` / `updated_at` on all three tables
  (AGENTS.md §7). Both have DB defaults so seed SQL is valid; the JPA entities maintain
  them for application writes via `@CreationTimestamp` / `@UpdateTimestamp`. This
  supersedes the plan §3 sketch, which listed audit columns only on `users`.
- **Audit fields on bulk UPDATE paths too**: `@UpdateTimestamp` only fires for entity saves, so
  every bulk `UPDATE` in this schema (the atomic verification update, the single-use rotation
  guard and the revoke-all update) sets `updated_at = now()` explicitly — otherwise AGENTS.md
  §7 is silently violated whenever a token or status changes through bulk SQL (AU-13, AU-16).
  Logout's bulk `DELETE` (AU-18) is exempt: it removes the row outright, leaving no
  `updated_at` to stamp.
- **Timestamps are `TIMESTAMPTZ`**, mapped to `Instant`, and compared in UTC — no
  server-timezone ambiguity in TTL math (FR-02, FR-04).
- **Primary keys are `UUID DEFAULT gen_random_uuid()`** (public identifiers are UUIDs,
  AGENTS.md §6); entities assign the id with Hibernate `@UuidGenerator`, and the `V2` seed
  supplies fixed UUIDs for the two demo accounts so integration tests can reference them.
- **Seed data (`V2`)**: `admin@foleybooks.com` (`ADMIN`) and `customer@foleybooks.com`
  (`CUSTOMER`), both `VERIFIED`, with pre-computed BCrypt-12 hashes. These are intentionally
  public portfolio credentials (AGENTS.md §5/§7), so committing their hashes violates
  nothing.
- **Cleanup**: no scheduled purge of expired or rotated rows in the MVP (YAGNI, C1).
  `refresh_tokens` grows with logins; `confirmation_tokens` stays at ≤ 1 row per account.
  Both stay correct because every query filters on `token_hash` or `user_id`, and expired
  rows are rejected at read time by `expires_at`, not by deletion.
- **No cross-service columns**: nothing in `auth_db` references `catalog_db` or `order_db`,
  and vice versa; `order-service` learns identity from JWT claims only (C18).

## Consequences

- AU-05 can be written as two migrations: `V1__create_auth_schema.sql` (3 tables,
  constraints, `ix_refresh_tokens_user`) and `V2__seed_demo_users.sql`. The `@DataJpaTest`
  gate (AU-06) proves `validate` matches these columns and that the uniques and hash
  lookups behave (plan §6.3).
- Rotation without deletion keeps an audit trail of every session a user ever had — a
  portfolio-positive signal for reviewers, and the mechanism LC-08 depends on. It also
  means the table grows with logins; acceptable at MVP scale (C1), revisit if it ever isn't.
- Retaining confirmation rows means a verified account keeps one spent row forever. AU-13
  must implement the already-confirmed branch as `findByHash` hit + `users.status =
  'VERIFIED'` → 200 idempotent, never as "no row found" → 410, or FR-02/LC-03 regression.
- `CHECK`-constraint enums make an invalid role/status fail loudly at the database rather
  than silently persisting garbage; adding a value later (e.g. a `SUSPENDED` user status)
  is a new, additive migration — never an edit to `V1` (C16).
- Hashed-token lookups require the client value to be re-normalized identically (lowercase
  hex SHA-256 of the exact presented token); a mismatch is a service bug that surfaces as
  an authentication failure, so `AuthFlowIT` (AU-19) covers the full round trip.
- The 15-minute access-token grace period after logout (FR-05 — the spec explicitly
  accepts it; spec §6 defers any tighter mechanism) is a documented, accepted gap —
  closing it would need a persisted deny-list or shorter access TTLs, both out of scope.
- No dependency is added by this decision; `pgcrypto` is not required because
  `gen_random_uuid()` has been core since PostgreSQL 13.

## Deviations

Recorded here because they touch documents that rank **above** this ADR in the
constitution's precedence order (constitution > specs > AGENTS.md > ADRs), so each needs
an explicit human-approved sync rather than a silent reinterpretation (C6).

- **AGENTS.md §5, refresh-token sentence** — the original wording ("rotated on use;
  revocation = row delete. Only refresh tokens are persisted") is incompatible with LC-08:
  deleting rotated rows erases the theft signal, and "only refresh tokens are persisted"
  contradicts FR-02's server-side invalidation of earlier confirmation links. AGENTS.md
  §5 has been updated to match this ADR (rotation keeps the spent row; logout deletes;
  confirmation hashes are persisted).
- **Plan §3, confirmation-token bullet** — the parenthetical "deletion on verification
  makes reuse detectable via the user status" is unachievable, as the row is precisely
  what links a spent token back to a user. This ADR retains the row. **The plan is
  product truth above ADRs, so it carries the same edit**: plan.md §3 and the §4
  `confirm` pseudocode were synced in the AU-13 follow-up (user-approved) — the
  deletion clause is gone and §2/§6 now document the retained-row contract.
- **Plan §4, `logout` pseudocode** — the sketch said `deleteByHash(sha256(refreshToken))`,
  an unguarded delete that would let a logout presenting a spent value destroy the
  `ROTATED` reuse evidence FR-04/LC-08 depend on (the same self-sabotage the
  delete-on-rotate paragraph above rules out). AU-18 implements the row delete guarded on
  `status = ACTIVE`. **The plan is product truth above ADRs, so it carries the sync**:
  plan.md §4 now reads `deleteActiveByHash` with the guard and its rationale spelled out
  (user-approved in the AU-18 review).

## References

- Spec 001: FR-01..FR-05, FR-14; LC-01..LC-04, LC-08..LC-09, LC-15, LC-19, LC-21,
  LC-23..LC-26
- Plan 001 §3 (auth_db sketch), §4 (register/confirm/refresh/logout pseudocode),
  §6.3 (schema tests); decisions D-03, D-04, D-05
- `docs/constitution.md`: C1, C16, C17, C18, C24
- AGENTS.md §5 (JWT/tokens/passwords), §6 (UUID identifiers), §7 (persistence + seed rules)
