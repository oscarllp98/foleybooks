# ADR-005: east-west book lookup and cart enrichment (Feign → catalog)

## Status

Accepted — implements the order-service half of plan §5 D-10; the catalog half
(the `GET /books/batch` read contract) already shipped as CA-11 under ADR-009.
This is the docs gate for FR-10/FR-11 and the read-side re-validation half of
FR-12/FR-13, and it is the prerequisite those requirements cannot be met without:
ADR-004 deliberately stores only intent, so no `CartResponse` exists until this
decision is implemented (OR-03 Feign config, OR-05 client + DTOs, OR-06 add,
OR-07 read). No deviation from higher-ranked documents — the spec and plan state
the *what* (live prices, one call, flagged-and-excluded lines, server totals);
this ADR fixes the *how* they left open: transport, contract coupling, and the
error semantics that keep a missing book distinct from an unreachable catalog.

## Context

`order_db` keeps `carts` and `cart_items` only (ADR-004): a line is `(book_id,
quantity)` and nothing else — no title, no cover, no price, no stock. That
omission is the whole reason this ADR exists. Every value FR-11 must render, and
every bound FR-10/FR-12 must enforce against live stock, lives in `catalog_db`,
which order-service may neither join to nor copy (C18: database-per-service, no
cross-DB references). So a cart read is inherently a **two-source operation**:
lines from the local repository, product data fetched from catalog-service over
the network. The forces on that fetch:

1. **Live, not copied** (FR-11, D-08): prices and stock are re-read on every
   cart access; a cached or stored copy is exactly the second, drift-prone truth
   ADR-004 refuses to hold and NFR-07 exists to prevent.
2. **One round-trip, not one-per-line** (D-10, NFR-02 ≤ 500 ms): enriching a
   cart of N lines must cost a constant number of calls. CA-11's `GET
   /books/batch?ids=` is the endpoint built for precisely this.
3. **East-west, never via the gateway** (AGENTS.md §4, C27): only the gateway is
   north-south / internet-facing; service-to-service traffic goes direct by
   registered service name, and the gateway owns no business logic to route this
   through.
4. **No shared library** (AGENTS.md §4): order-service cannot import
   catalog-service's `BookResponse` record, so the consumed shape is re-declared
   on the consumer side — which raises a coupling question plan §2 leaves open.
5. **A gap and a failure are different things** (LC-14 vs LC-30 vs NFR-06): a
   book that no longer exists must flag its line `available=false` (LC-14); a
   book that exists but is now short of the line quantity must flag
   `insufficientStock` (LC-30); a catalog that is simply *down* must not be
   laundered into either flag, because "absent" and "could not ask" are different
   facts and the first zeros a total while the second knows nothing.
6. **Catalog reads are public** (C22, ADR-008): every `GET /api/v1/books/**` is
   on the anonymous allowlist, which changes what the east-west call must (not)
   carry.

The catalog service is also a seeded, static, read-only model in the MVP (spec
§6): a book vanishing or its stock dropping is a *defensive* state, exercised in
tests but not produced by any MVP write — LC-14/LC-30 exist precisely so the
deferred checkout spec inherits live re-validation, not a stale assumption.

## Decision

OpenFeign drives the transport, a consumer-owned subset DTO defines the
contract, and the enrichment/flagging/totaling rules live in `CartService`. The
catalog-side read is consumed exactly as ADR-009 CA-11 published it — this ADR
adds nothing to catalog and makes no schema decision; it decides only how
order-service talks to it and what it derives from the answer.

### Transport and addressing

- `cart/client/CatalogClient` is a `@FeignClient(name = "catalog-service")`. The
  target is the **Eureka-registered service name**, resolved to a live instance
  by Spring Cloud LoadBalancer over the compose network — never a host:port
  literal, never the gateway's `lb://` indirection, never `http://localhost`
  (AGENTS.md §4 east-west rule). This is the same registry ADR-006 secures;
  order-service reaches catalog the way the gateway does, but point-to-point.
- The paths are the **gateway-identical** `/api/v1/books/{id}` and
  `/api/v1/books/batch`: the gateway routes `/api/v1/books/**` to
  `lb://catalog-service` with **no prefix rewrite**, and `BookController` is
  mapped at `/api/v1/books`, so the absolute path a client sends north-south is
  the same one order-service sends east-west. The only thing that differs is the
  authority (`catalog-service` instead of the gateway origin).
- The client declares the two operations the two flows need, mirroring
  ADR-009's asymmetry: `BookDto findBook(UUID id)` → `GET /api/v1/books/{id}`
  (a singular read that can 404), and `List<BookDto> batchBooks(Collection<UUID>
  ids)` → `GET /api/v1/books/batch` (a keyed read whose missing entry is data,
  not an error). The batch sends repeated `ids=` parameters, the form Spring binds
  to `List<UUID>` on the catalog side.

### The consumed contract is a reader subset, owned by the consumer

- `cart/client/BookDto` carries **exactly the fields a cart line needs**:
  `id, title, author, coverUrl, price, stockQuantity`. It is a deliberate
  *narrowing* of the 9-field `BookResponse` ADR-009 publishes — `isbn`,
  `availability` and the nested `category` are dropped because FR-11's line and
  the plan §2 cart JSON do not show them.
- Because the record is narrower than the wire body, Jackson must tolerate the
  unknown surplus it receives: the client's mapper is configured
  `FAIL_ON_UNKNOWN_PROPERTIES = false`, so catalog **adding** a `BookResponse`
  field can never break cart reads. The coupling that *remains* is the one the
  product actually promises: renaming or removing any of the six consumed
  fields is a breaking change for order-service, caught at deserialization.
  That is consumer-driven — the cart depends on price and stock, not on the
  catalog's internal presentation — and it is the reason a second DTO exists
  instead of importing `BookResponse`, which AGENTS.md §4 forbids.
- `price` deserializes to `BigDecimal` and `stockQuantity` to `int`, the same
  types the source publishes (D-08, ADR-009); nothing is widened to `double`
  anywhere across the hop (NFR-07).

### No token relay east-west

- catalog GETs are anonymous (C22), so `CatalogClient` calls them **without
  forwarding the caller's access token**. There is no `RequestInterceptor` adding
  an `Authorization` header, and none is added speculatively (C1). The user's
  bearer token is validated once, at order-service's own security boundary, to
  select *whose* cart this is (ADR-004's `sub` → `carts.user_id`); it is then
  discarded as far as the east-west hop is concerned. Identity is a north-south
  concept that never crosses into catalog, which is also why catalog holds no
  user data at all.
- Direction check, stated once to avoid the obvious confusion: **caller → order**
  requires a valid JWT (D-14, order's resource server, ADR-008); **order →
  catalog** requires none.

### Absence is data; unavailability is an error

The three outcomes a cart read can observe must not collapse into one another.
This is the load-bearing decision, and it is why the client decodes failures at
the transport boundary instead of letting raw exceptions into the service:

- **`findBook` on a well-formed id that names no book** → catalog answers
  `404` (`urn:foley-books:problem:book-not-found`, ADR-009). `FeignConfig`'s
  `ErrorDecoder` turns that 404 into the order-domain
  `BookNotFoundException`, so the service never handles a transport type (C7,
  C8) and simply lets it propagate; the order `GlobalExceptionHandler` renders
  the client-facing `404` for `POST /cart/items` (plan §2). This is the concrete
  form of plan §4's `if book == null: return 404` — pseudocode null becomes a
  domain exception raised at the client boundary, preserving the intent (a
  singular lookup has a not-found answer) without a transport `if` in the
  service.
- **`batchBooks` with a book gone** → not a 404 (ADR-009 inverts that here): the
  id is simply **absent from the returned array**. That gap is the signal, read
  by the enrichment step into `available = false` (LC-14). One dead line can
  never fail the whole cart read, because the batch never errors for a missing
  entry.
- **A catalog that is down, timing out, or 5xx-ing** → a `FeignException` /
  connect failure that is *not* the specific 404 the decoder maps, so it stays a
  transport failure. It must surface as a generic, traceable error (D-15,
  NFR-06) and must **never** be reported as `available = false`. Conflating "I
  could not ask" with "the book is gone" would silently flag-and-exclude every
  line, zero the grand total, and hand the user a confidently wrong cart —
  a money lie NFR-07 forbids and the exact second-truth ADR-004 refuses. The
  honest behavior is fail-fast: an unreadable catalog yields an error, not a
  fabricated read.

> Load-bearing part: *never fabricate a cart from an unavailable dependency.*
> The specific status the residual `FeignException` maps to (`503` with a
> dependency URN being the accurate default; a generic `500` is acceptable) is an
> OR-03 handler detail, adjustable without an ADR change — the spec names no
> catalog-outage UX, and NFR-06 only guarantees a generic body with a `traceId`.
> No fallback / stale-serving / degraded-render is built (C1): the MVP catalog
> is always-on (spec §6), so this path exists to be correct under test, not to be
> exercised in the demo.

### Enrichment, flags and totals (all in `CartService`, C8)

`CartService.read(userId)` is the plan §4 pseudocode made concrete:

1. Load the cart's lines from the local repository (ADR-004).
2. Collect the line book ids and fetch product data in **one**
   `batchBooks(ids)` call — the D-10 batch, so the call count is independent of
   line count. Because ADR-009/CA-11 cap a batch at `MAX_SIZE` (100) ids, ids are
   partitioned into chunks of ≤ 100 and each chunk fetched, results concatenated:
   a realistic cart is one HTTP round-trip, and the partition is a correctness
   backstop so a > 100-line cart cannot 400 the whole read *or* silently skip
   stock re-validation for the overflow (FR-11 promises re-validation of every
   line, not of the first 100). It does not weaken D-10 — D-10 forbids
   one-call-per-line, and this stays `ceil(lines / 100)` calls.
3. Key the returned list into a `Map<UUID, BookDto>` by `id`; **never rely on
   response order** (ADR-009: batch order is not contractual).
4. For each line, in the order the repository returned it: look the id up in the
   map and derive `available = bookPresent`,
   `insufficientStock = available && quantity > stockQuantity`.
5. Compute the money and the total (below), then return a `CartResponse`.

### Money (D-08, NFR-07) — computed here, never downstream

- `lineTotal = book.price().multiply(BigDecimal.valueOf(quantity))`. An integer
  multiplier cannot add decimal places to a scale-2 price, so the product is
  exact at scale 2 and **no `RoundingMode` ever applies** — that is *why* the
  total is exact rather than approximate, and the reason `double` is excluded at
  every step.
- `total = Σ lineTotal` over lines that pass **both** gates (`available &&
  !insufficientStock`); flagged lines contribute nothing (D-10). The running sum
  of scale-2 values stays scale 2; a final `setScale(2)` is a serialization
  guarantee, never a rounding event. All of it is computed server-side and the
  frontend only renders (D-08).

### The response shape, and what stays null

- `CartResponse { items: [CartItemResponse], total, currency }`,
  `CartItemResponse { bookId, title, author, coverUrl, unitPrice, quantity,
  lineTotal, stockQuantity, available, insufficientStock }` — the plan §2
  contract. Records in, records out; no entity and no `BookDto` is serialized
  directly to the client (C9); `CartService` projects both sources into this
  response record.
- The line's public handle is **`bookId`**, not the catalog DTO's `id` field
  name and not `cart_items.id`: the id is re-labelled because a cart line must be
  addressable for PATCH/DELETE `/cart/items/{bookId}` (plan §2) and because
  `cart_items.id` / `carts.id` never leave the service (ADR-004: the cart is
  addressed by identity, the line by book). The `bookId` is the same public UUID
  the catalog published (AGENTS.md §6).
- An **unavailable line carries `bookId` + `quantity` + `available=false`** and
  leaves every catalog-sourced field (`title`, `author`, `coverUrl`, `unitPrice`,
  `lineTotal`, `stockQuantity`) **`null`** — not `0.00`, not `""`, not a made-up
  `OUT_OF_STOCK` price. `null` is the honest encoding of "there is no book to
  read," and it is why such a line is excluded from the total: you cannot sum a
  price that does not exist. An **insufficient line** is fully populated (the
  book exists, its price and current stock are known and shown so the user can
  act on LC-30); its `lineTotal` is its true product, but it is **excluded from
  the grand total** by the flag — the UI renders the line, and the total
  reflects only what the user can actually check out in the deferred spec.
- `currency` is the constant `"EUR"` on the response, never a column (ADR-004).

### Layering

- `CartService` owns the flags, the total, the chunking and the id-keying — all
  business rules, in the service, per C8.
- `cart/client/` is a transport adapter and nothing more: it converts a
  catalog response/`404` into `BookDto`/domain exception, holds no rule (no
  stock math, no flagging), so `FeignException` never reaches the service
  (C7). The `ErrorDecoder` and Jackson tolerance live in `config/FeignConfig`.
- The repository only persists (ADR-004); controllers stay thin (validate,
  delegate, map).

## Consequences

- **Sequencing hardens into the task list.** OR-05 (`CatalogClient` + `BookDto`
  + Feign decode) must land before OR-06/OR-07, because neither `cart.add`'s
  stock bound nor `cart.read`'s `CartResponse` can be built without the lookup;
  ADR-004 already flagged enrichment as a prerequisite rather than an
  optimization.
- **The read is a two-source join done in application code.** Any cart read
  touches the network, so `CartService.read` must run the Feign call *outside*
  the DB transaction (fetch catalog data, then map; do not hold a DB connection
  open across the hop) and must tolerate a partial catalog answer without
  corrupting stored intent — flags are computed at read time, never written back.
- **Add/patch reuse the single lookup, read reuses the batch.** `findBook`
  serves FR-10/LC-12 (name one book, get a clean 404-vs-stock answer) and
  FR-12/LC-16 (OR-08 re-validates the target line's stock before writing);
  `batchBooks` serves FR-11/LC-14/LC-30 (enrich many lines, read the gap). The
  client carries both because the flows name different cardinalities, not to
  offer a choice.
- **Consumer subset keeps catalog free to grow.** Catalog may add
  `BookResponse` fields without an order-service change; the six consumed fields
  are the contract boundary and are pinned by the mapping/decode tests below.
- **Downtime is visibly downtime.** A cart cannot be read while catalog is
  unreachable, which for the always-on MVP demo is effectively never but is
  exercised by a negative test; the alternative (flag-and-exclude-everything)
  was rejected as an NFR-07 violation, and this ADR is the record that forbids
  re-introducing it.
- **Tests (plan §6).** `cart_read_unavailableBook_flagsLine` (LC-14) and
  `cart_read_insufficientStock_flagsLine` (LC-30) pin the two flags and, crucially,
  that neither is how a dependency failure is reported; `money_lineTotals_exact`
  (D-08/NFR-07) pins scale-2 exactness and total-exclusion-of-flagged-lines;
  `cart_add_aboveStock_rejectedWithAvailableStock` (LC-12) pins the `findBook` →
  422/`availableStock` path; `CartFlowIT` (plan §6.4, `@MockitoBean CatalogClient`)
  exercises add → change → remove → flags → totals across one stubbed client,
  which is also where the batch "absent, never 404" consumption is proven.
- **No new dependency** beyond the AGENTS.md §4-mandated
  `spring-cloud-starter-openfeign` (pre-approved by the stack, so §12.4 is not
  re-triggered); **no schema change** — this reads catalog's existing API and
  writes only `order_db` as ADR-004 defines it.

## References

- Spec 001: FR-10, FR-11 (FR-12/FR-13 read-side re-validation); LC-12, LC-13,
  LC-14, LC-16, LC-30; NFR-02, NFR-05, NFR-06, NFR-07
- Plan 001: §2 (cart JSON contract + endpoint map: `/books/batch`, `/cart*`),
  §3 (order_db intent-only), §4 (`cart.add` / `cart.read` pseudocode), §5
  (D-08 money, D-10 the decision this realizes), §6.1/§6.2/§6.4 (unit, web and
  `CartFlowIT` test names)
- `docs/constitution.md`: C1, C7, C8, C9, C15, C18, C22, C23, C27
- AGENTS.md §4 (east-west OpenFeign, database-per-service, no shared library,
  gateway-only north-south), §5 (JWT at the boundary, catalog GETs public),
  §6 (records in/out, UUID identifiers), §7 (persistence)
- ADR-004 (order_db stores intent only — the storage this reads against);
  ADR-009 CA-11 (the `GET /books/batch` contract consumed here: bare array,
  absent-not-404, `MAX_SIZE` cap, order-not-contractual, `BookResponse` shape);
  ADR-008 (order's own resource server authenticates the caller; catalog's public
  GET allowlist makes relay unnecessary); ADR-006 (Eureka registration the
  service-name target resolves through); ADR-003 (`stock_quantity` is the single
  stored truth enrichment reads live)
