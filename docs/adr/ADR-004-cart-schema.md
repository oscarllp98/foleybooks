# ADR-004: order_db schema (carts, cart_items)

## Status

Accepted — implements the Spec 001 plan §3 sketch for order_db; prerequisite docs
gate for OR-04 (Flyway V1 + entities/repositories) and the storage half of
FR-10..FR-13. No deviation from higher-ranked documents: the plan sketch stays
intact and this ADR only fills in the details it left open (nullability, PKs,
checks, indexes, cascade behavior).

## Context

order-service owns `order_db` exclusively (C18: no other service may reference it)
and must persist exactly one thing for the MVP: the **per-user persistent cart** —
a server-side collection of "book id + quantity" pairs that satisfies FR-10..FR-13.

What the requirements actually put on disk is surprisingly small, and the design
work is mostly deciding what must **not** be stored:

1. **One cart per user, shared by every session and device** (FR-10, FR-11,
   LC-17): two concurrent sessions must see and mutate the same lines and the
   same totals. The cart is identified by the caller's authenticated identity —
   the `sub` claim of the access token — and never by a client-supplied cart id.
2. **At most one line per book** (FR-10, LC-13): re-adding an existing book
   increases its quantity, including when the second add arrives from another
   device racing the first.
3. **A quantity that is always ≥ 1 and is capped by catalog stock** (FR-10,
   FR-12, LC-12, LC-16): setting quantity to 0 *removes* the line, so a
   zero-quantity row is not a state the schema may express.
4. **Live prices and live stock** (FR-11, D-08): lines show the book's current
   unit price and stock is re-validated on every read. Stock and price, however,
   are owned by `catalog_db`, which this database may neither reference nor copy
   (C18).

The schema must survive `ddl-auto=validate` (C16, C17) with plain JPA entities,
need no index beyond what the data volume requires (NFR-02), and hold no PII
beyond the user's UUID (NFR-05).

## Decision

Two tables, one schema (`public`), PostgreSQL 16. Flyway owns every column;
entities never generate DDL. Same column-type vocabulary as ADR-002/ADR-003 —
`UUID`, `INTEGER`, `TIMESTAMPTZ` — every one of them a stock Hibernate 6 type, so
`validate` has no custom-type surface to reject.

### carts

```sql
CREATE TABLE carts (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_carts_user UNIQUE (user_id)
);
```

- `user_id` is the auth-service user UUID taken from the JWT `sub` claim, with
  **no foreign key**: `auth_db` is exclusively owned by auth-service (C18), so
  referential integrity here is impossible by design and *identity integrity* is
  instead guaranteed by signature validation (ADR-008). The value is never taken
  from a request parameter, so a client cannot name another user's cart — there
  is no cart id in the API surface at all.
- `uk_carts_user` is the whole of LC-17: **one cart per user is a database
  invariant, not a service convention.** It also makes the find-or-create on
  first add race-safe with no lock or serialization — two concurrent
  registrations of a cart from two devices produce exactly one row, the loser
  catches the unique violation and re-reads, which is the same pattern AU-12
  uses for duplicate registration (LC-19).
- **No `currency`, no `total`, no `item_count` column.** EUR is a system-wide
  convention, not per-row data (same reasoning ADR-003 gives for books), and a
  stored grand total would be a second, drift-prone truth that D-08 explicitly
  forbids: totals are computed server-side at read time from live catalog data
  (ADR-005), never persisted. A stale total is exactly the money-integrity bug
  NFR-07 exists to prevent.

### cart_items

```sql
CREATE TABLE cart_items (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    cart_id    UUID        NOT NULL,
    book_id    UUID        NOT NULL,
    quantity   INTEGER     NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_cart_items_cart      FOREIGN KEY (cart_id) REFERENCES carts (id)
                                       ON DELETE CASCADE,
    CONSTRAINT uk_cart_items_cart_book UNIQUE (cart_id, book_id),
    CONSTRAINT ck_cart_items_quantity  CHECK (quantity >= 1)
);
```

- `book_id` is an **opaque UUID with no FK** (C18) — the plan §3 sketch states it
  explicitly and it is the constitutional reason this schema is interesting. The
  database cannot know whether a book exists, so a line whose book vanished is a
  representable state, which is exactly what LC-14 needs (`available=false`).
  Existence is resolved at read time by the batch lookup, which answers a
  **missing entry, never a 404** (ADR-009 CA-11) — the flag is read from the gap.
- `uk_cart_items_cart_book` is FR-10's "one line per book" and LC-13's
  cross-device sum, both enforced at rest: a concurrent second add of the same
  book cannot create a duplicate line, only a unique violation the service
  handles by summing (capped by stock). Its **leftmost prefix `cart_id` is also
  the index for the dominant read** — "all lines of this cart" — so no separate
  `ix_cart_items_cart` is added; a second index on the same leading column would
  be decorative at this size (C1), and the escalation path is a new migration,
  never an edit to this one (C16).
- `fk_cart_items_cart … ON DELETE CASCADE`: this FK is *intra*-service, so unlike
  `book_id` it is expressible, and cascade is the right verb because a cart line
  has no meaning apart from its cart. Compare ADR-003's `ON DELETE RESTRICT`
  against `categories`, where the child is a first-class catalog entity worth
  protecting; here the child is pure cart state. No MVP flow deletes a cart row
  (see *Cleanup* below), so the cascade is a correctness guarantee for whoever
  first does, not dead weight.
- `ck_cart_items_quantity CHECK (quantity >= 1)` is the **only stock-adjacent
  rule the database can legally hold**. There is deliberately **no upper bound**:
  the cap is `books.stock_quantity`, which lives in another service's database
  and can change between reads, so any ceiling here would be a stale copy (C18).
  The bound is applied in the service against a live catalog answer — rejected
  with `availableStock` on write (LC-12), flagged `insufficientStock` on read
  (LC-30). Likewise FR-12's "0 removes the line" is a delete, not a stored zero:
  the check makes the forbidden state unrepresentable instead of merely
  unexpected. Absurd inputs (negatives, huge numbers, non-numeric) are refused at
  the boundary by Bean Validation on the request record (C23); the `CHECK` is the
  at-rest backstop, the same two-layer posture `ck_users_email_lower` uses in
  ADR-002.
- **No price, title, cover or stock snapshot on the line.** Storing the unit
  price at add time would create a second truth the moment catalog corrects a
  price, and FR-11 asks for the current price. The line's *only* job is to record
  intent ("this user wants N of this book"); every displayable value is enriched
  per read (D-10, ADR-005). A point-in-time price becomes a requirement at
  **order** time, not cart time — that belongs to the deferred checkout spec
  (spec §6), which will own its own tables and its own ADR.
- `quantity INTEGER` (not `SMALLINT`) mirrors `books.stock_quantity` (ADR-003) so
  the two quantities are directly comparable in service code and in tests without
  a widening cast.

### Cross-cutting rules

- **Audit columns on both tables** (`created_at` / `updated_at`, AGENTS.md §7),
  `TIMESTAMPTZ` mapped to `Instant` and maintained by `@CreationTimestamp` /
  `@UpdateTimestamp`, exactly as in ADR-002/ADR-003. On `cart_items` they carry a
  little extra meaning: `updated_at` is how a support or future analytics view
  distinguishes a re-added line from a long-lived one. Both have DB defaults so
  hand-written SQL stays valid.
- **Primary keys are `UUID DEFAULT gen_random_uuid()`** with entities assigning
  via Hibernate `@UuidGenerator` (public identifiers are UUIDs, AGENTS.md §6).
  Note the asymmetry: `carts.id` is a PK that never surfaces in the API (the cart
  is addressed by identity, not by id), while `cart_items.book_id` is a foreign
  *public* identifier of another service, which is legal precisely because
  identifiers, not joins, are what cross service boundaries.
- **Naming**: snake_case columns and `uk_` / `fk_` / `ck_` / `ix_` prefixes, so
  all three schemas read as one design language.
- **No seed migration.** Unlike auth (`V2` demo users) and catalog (`V2` 12
  books), order_db has no FR-14 fixture: a cart is private user state, and
  FR-14's demo accounts start with an empty cart, which FR-11 must render as its
  empty state. Seeding cart rows would assert nothing about the product.
- **Cleanup**: no cart expiry or purge job in the MVP (YAGNI, C1) — FR-11 makes
  persistence the requirement, and checkout (which would empty a cart) is
  explicitly out of reach (spec §6). Consequence, stated plainly: if a user is
  ever deleted from `auth_db` by a future spec, their cart row and lines become
  orphans *here*, unreachable by anyone (the `sub` no longer authenticates),
  because no cross-service cascade can exist. Accepted, since no MVP flow deletes
  users.

## Consequences

- OR-04 can be written as one migration, `V1__create_cart_schema.sql` (2 tables,
  1 FK, 2 uniques, 1 check), plus `Cart` / `CartItem` entities and repositories;
  the `@DataJpaTest` gate (plan §6.3; C15 — Testcontainers PostgreSQL, H2
  forbidden) proves `uk_carts_user`, `uk_cart_items_cart_book`, the
  `quantity >= 1` violation, the cascade, and that `validate` matches these
  columns.
- Because the schema stores *only* intent, every cart read is a two-source
  operation, so ADR-005 (Feign batch + enrichment) is not an optimization but a
  prerequisite for FR-11: no `CartResponse` can be built without it, and OR-05
  must land before OR-06/OR-07.
- LC-14 and LC-30 become *read-side* outcomes rather than write-side guards.
  Implementations must resist the tempting "helpful" behaviors that would break
  this: silently dropping lines whose book vanished, or auto-clamping a quantity
  that exceeded current stock. Both states are legal at rest and must surface as
  flags, or FR-11's re-validation semantics and the deferred checkout spec lose
  their evidence.
- The `CHECK (quantity >= 1)` means FR-12's set-to-zero path must be a delete; a
  service that updates the row to 0 first fails with a constraint violation
  (500-class) instead of the 200 `CartResponse` plan §2 specifies for that branch.
  OR-08 owns it, and `cart_add_aboveStock_rejectedWithAvailableStock` /
  `money_lineTotals_exact` (plan §6.1) pin the surrounding behavior.
- No dependency is added by this decision; `gen_random_uuid()` has been core
  since PostgreSQL 13, so `pgcrypto` is not required (same note as ADR-002/003).

## References

- Spec 001: FR-10..FR-13; LC-12, LC-13, LC-14, LC-16, LC-17, LC-27, LC-30;
  NFR-02, NFR-05, NFR-07
- Plan 001 §2 (cart JSON contract + endpoint map), §3 (order_db sketch), §4
  (`cart.add` / `cart.read` pseudocode), §6.1/§6.3 (unit and schema tests);
  decisions D-08, D-10
- `docs/constitution.md`: C1, C15, C16, C17, C18, C23
- AGENTS.md §4 (database-per-service), §6 (UUID identifiers), §7 (persistence +
  seed rules)
- ADR-002 / ADR-003 (design language: constraint prefixes, UUID PKs, audit
  columns, "refuse to store a second truth"); ADR-008 (where `user_id` comes
  from); ADR-009 CA-11 (the batch read whose "absent, never 404" contract this
  schema depends on); ADR-005 (enrichment built on this storage, delivered by
  OR-02)
