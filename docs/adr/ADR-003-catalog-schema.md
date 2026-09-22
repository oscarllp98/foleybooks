# ADR-003: catalog_db schema (categories, books)

## Status

Accepted — implements the Spec 001 plan §3 sketch for catalog_db; prerequisite docs
gate for CA-03 (Flyway V1 + entities/repositories) and CA-04 (Flyway V2 seed).
No deviation from higher-ranked documents: the plan sketch stays intact and this
ADR only fills in the details it left open (nullability, PKs, lengths, indexes).

Schema note, refined by ADR-009 (not a schema change): this ADR says availability
is *never persisted* and stays true; ADR-009 fixes where the derived value is
computed (`AvailabilityPolicy` in the service layer) and the resulting
`BookResponse` field list, including the published `stockQuantity`.

## Context

catalog-service owns `catalog_db` exclusively (C18: no other service may reference
it) and must persist exactly two things to satisfy FR-06..FR-09 and FR-14:

1. **Categories** — a flat, named classification. FR-09: *all books belong to
   exactly one category*, the category list is browsable, and an empty category
   (or an empty list) is a valid read state, never an error (LC-31).
2. **Books** — title, author, ISBN, EUR price, cover image URL, category, and
   stock. FR-06 lists cover/title/author/price paginated and sorted by
   title/price; FR-07 adds ISBN, category and *derived* availability to the
   detail view; FR-08 searches `title`/`author` with partial, case-insensitive
   matching (D-06: PostgreSQL `ILIKE`), combinable with a `categoryId` filter.

The MVP catalog is **seeded read-only** (spec §6 defers admin CRUD; FR-14): the
only writes are the `V2` seed migration and, in a future spec, stock deduction at
checkout. Nothing in the schema may assume the seed is the last word — e.g. stock
must stay the single mutable source of truth so a future `UPDATE` can change it.
The admin CRUD of the eventual end state (AGENTS.md §2: "public read, admin
write") arrives with a future spec; this read model is exactly the schema those
endpoints will extend, not a departure from it.

The schema must survive `ddl-auto=validate` (C16, C17) with plain JPA entities,
serve NFR-02 (≤ 500 ms p95 locally) without any index beyond what the data volume
needs, and hold no PII beyond book metadata (NFR-05).

## Decision

Two tables, one schema (`public`), PostgreSQL 16. Flyway owns every column;
entities never generate DDL. No native Postgres types beyond `UUID`,
`VARCHAR`, `NUMERIC(10,2)`, `INTEGER` and `TIMESTAMPTZ` — every one of them maps
to a stock Hibernate 6 type, so `validate` has no custom-type surface to reject.

### categories

```sql
CREATE TABLE categories (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_categories_name UNIQUE (name)
);
```

- `uk_categories_name` is the dedupe mechanism: category identity in the seed (and
  in any future admin import) is the name, and the FR-09 list reads as a set of
  distinct names. `VARCHAR(100)` is generous headroom for a display label.
- **No slug / no description columns** — not in the spec (YAGNI, C1). The filtering
  contract is `categoryId` (UUID, D-06), so a URL-friendly slug has no consumer.
- A category with zero books is **fully legal at rest** (LC-31): the FK lives on
  `books`, so an empty category is just a row no book references; the empty state
  is a read-side outcome, not a constraint workaround.

### books

```sql
CREATE TABLE books (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id    UUID          NOT NULL,
    title          VARCHAR(300)  NOT NULL,
    author         VARCHAR(200)  NOT NULL,
    isbn           VARCHAR(20)   NOT NULL,
    price          NUMERIC(10,2) NOT NULL,
    stock_quantity INTEGER       NOT NULL,
    cover_url      VARCHAR(500)  NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_books_category FOREIGN KEY (category_id) REFERENCES categories (id)
                                 ON DELETE RESTRICT,
    CONSTRAINT uk_books_isbn     UNIQUE (isbn),
    CONSTRAINT ck_books_price    CHECK (price >= 0),
    CONSTRAINT ck_books_stock    CHECK (stock_quantity >= 0)
);

CREATE INDEX ix_books_category ON books (category_id);
```

- `category_id NOT NULL` puts FR-09's "exactly one category" **in the database**
  rather than in application convention: a book cannot exist uncategorized, and
  one row (not a join table) is what makes it exactly one. `ON DELETE RESTRICT`
  means a category still holding books can never be deleted — a loud failure in
  any future admin path, never silent orphaning or cascade data loss.
- `uk_books_isbn` is the data-integrity anchor of the whole read model: FR-14
  covers are hotlinked **by ISBN** (D-11), so a duplicate ISBN would mean two
  indistinguishable entries of one real book. ISBN is stored normalized
  (hyphen-free, `9780132350884`, never `978-0-13-235088-4`) — the legacy
  10-char ISBN-10 form (last digit may be `X`) fits, and `VARCHAR(20)` leaves
  headroom without pretending to validate checksums. Checksum validation is not
  a spec rule (C1); a future admin API adds it at the boundary (C23), not as a
  `CHECK` here.
- `price NUMERIC(10,2)` is D-08 verbatim: exact EUR decimal values, mapped to
  `BigDecimal` with scale 2; the entity must declare
  `@Column(precision = 10, scale = 2)` or Hibernate `validate` fails. There is
  **no currency column** — the spec is single-currency EUR and multi-currency is
  explicitly deferred (spec §6); EUR is a transport convention of the API
  (plan §2 JSON), not per-row data. `ck_books_price` allows `0.00` (a free
  giveaway title stays representable) but rejects negatives at rest.
- `stock_quantity INTEGER` + `ck_books_stock ≥ 0` is the **single stored
  representation of stock**. Availability (`OUT_OF_STOCK` 0 / `LOW_STOCK` 1–5 /
  `IN_STOCK` > 5, D-09/FR-07) is **never persisted**: a derived enum in
  `BookResponse` (CA-05) computed from the current row. Storing both would create
  a second, drift-prone truth the moment anything changes stock — and the
  deferred order spec will deduct stock precisely so such drift is a bug, not a
  schema migration. `INTEGER` (not `SMALLINT`) leaves room for realistic
  quantities and future restocking without a type-change migration.
- `cover_url NOT NULL` stores the full hotlink
  (`https://covers.openlibrary.org/b/isbn/<isbn>-L.jpg`, D-11). Storing it rather
  than deriving it from ISBN in code keeps the service a plain read model and
  makes a future CDN swap a data migration. It is `NOT NULL` because FR-06/FR-14
  show a cover for every book; a *broken* URL at render time is the frontend's
  placeholder job (LC-29), not a schema state. `NOT NULL` also means the seed
  cannot silently ship a coverless book — the constraint catches it in
  `@DataJpaTest` (plan §6.3).
- `ix_books_category` serves the FR-08/FR-09 `categoryId` filter and the
  future per-category admin count; it also keeps the FK check cheap on insert.
- **No search index.** `ILIKE '%q%'` (D-06) cannot use a B-tree, and a
  `pg_trgm` GIN index is deferred, not rejected: at seed scale (≥ 12 rows) a
  sequential scan clears NFR-02's 500 ms budget by orders of magnitude, and C1
  says don't build for volume the spec forbids. The escalation path is one new
  migration (`CREATE EXTENSION pg_trgm` + GIN index) when a real catalog exists.
- **No sort index.** FR-06 sorting (whitelisted `title`/`price` asc/desc, D-06)
  sorts the whole 12-row table per query; an index would be decorative at this
  size and the same migration-adding rule applies.
- **No category name copy on `books`.** `BookResponse` embeds
  `{ id, name }` (plan §2) via the FK join; duplicating `category_name` onto the
  row would go stale the first time a name is ever corrected.

### Seed data (V2, D-12 / FR-14)

- 3 categories + 12 real books with Open Library Covers URLs, at least one book
  with `stock_quantity = 0` and at least one with `1 ≤ stock_quantity ≤ 5`, so
  all three availability states are demonstrable in the UI without touching the
  database.
- **Fixed UUIDs for every seed row** (categories and books), mirroring ADR-002's
  demo users: catalog-service's own `@DataJpaTest` and integration tests
  (CA-06..CA-11) reference books by stable id instead of by title lookup.
  order-service never sees these rows (C18) — its Feign-stubbed ITs use
  synthetic UUIDs.
- Cover attribution (Open Library) lands in README with WR-03 (D-11).

### Cross-cutting rules

- **Audit columns everywhere**: `created_at` / `updated_at` on both tables
  (AGENTS.md §7), `TIMESTAMPTZ` mapped to `Instant` (UTC, no server-timezone
  ambiguity — same rule as ADR-002). Both have DB defaults so seed SQL is valid;
  entities maintain them for application writes via `@CreationTimestamp` /
  `@UpdateTimestamp`.
- **Primary keys are `UUID DEFAULT gen_random_uuid()`**, entities assign via
  Hibernate `@UuidGenerator` (public identifiers are UUIDs, AGENTS.md §6). The
  order-service cart references `book_id` as an opaque UUID with **no FK** —
  the cross-service boundary holds by construction: `catalog_db` is exclusively
  owned here (C18) and exposes no reference to any other service's data.
- **Naming**: snake_case columns, constraint prefixes `uk_` / `fk_` / `ck_` /
  `ix_` exactly as in ADR-002's `V1`, so both migrations read as one design
  language.
- **No secrets, no PII**: book metadata is public catalog data; nothing in
  `catalog_db` relates to users (identity arrives via JWT claims only, C18).

## Consequences

- CA-03 can be written as one migration, `V1__create_catalog_schema.sql` (2
  tables, constraints, `ix_books_category`), plus entities and repositories; the
  `@DataJpaTest` gate (plan §6.3; C15 — real PostgreSQL via Testcontainers, H2
  forbidden) proves the ISBN uniqueness, the NOT NULL category FK, the
  price/stock `CHECK`s, and that `validate` matches these columns (including
  `precision/scale` on `price`).
- CA-04 writes `V2__seed_catalog.sql` only — no seed logic ever exists in Java
  (C16). The stock fixtures (0 and 1–5) make FR-07's derivation testable
  end-to-end during WR-02 without mutations.
- Keeping availability derived (D-09) makes CA-05 a pure function test
  (`availability_thresholds`, plan §6.1) and means no future stock change can
  desync a badge — there is only one number to be right about.
- The `RESTRICT` FK and `NOT NULL` category mean the future admin spec must move
  or delete a category's books first — deliberate friction against accidental
  catalog loss.
- Search and sort stay index-free; if the catalog ever grows past "portfolio
  demo" scale, the response is a *new* migration adding `pg_trgm`/B-tree
  indexes, never an edit to `V1` (C16). Both tables are ready for that without
  column changes.
- No dependency is added by this decision; `gen_random_uuid()` has been core
  since PostgreSQL 13, so `pgcrypto` is not required (same note as ADR-002).

## References

- Spec 001: FR-06..FR-09, FR-14; LC-29, LC-31; NFR-02, NFR-05, NFR-07
- Plan 001 §2 (endpoint map + Book JSON), §3 (catalog_db sketch), §6.1
  (`availability_thresholds`), §6.3 (schema tests); decisions D-06, D-08, D-09,
  D-11, D-12
- `docs/constitution.md`: C1, C15, C16, C17, C18, C23
- AGENTS.md §6 (UUID identifiers), §7 (persistence + seed rules)
- ADR-002 (design language: constraints, audit columns, UUID PKs, fixed-UUID
  seeds); ADR-005 (cart enrichment via `GET /books/batch` reads this schema);
  ADR-009 (where the derived badge is computed, and the resulting
  `BookResponse` field list)
