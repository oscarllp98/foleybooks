# ADR-009: catalog read-model mapping conventions and the published book shape

## Status

Accepted — documents the mapping pattern delivered by CA-05 (spec 001, Phase 2) and
resolves two questions AGENTS.md §3/§6 and the plan leave open: where a derived
response field is computed, and which read-model conventions every later mapper
must follow. Binding for CA-06..CA-11 (catalog reads and `GET /books/batch`) and
for OR-05 (the Feign DTOs that consume them). ADR-004 (cart schema) has since
landed with OR-01; ADR-005 remains reserved by plan §5 for the batch-enrichment
decision.

## Context

CA-05 introduced the repository's first MapStruct mappers. Unlike auth-service —
whose responses are hand-built from values its services already hold — catalog
maps **JPA entities** to records, which forces three decisions the governing
documents state as principles but not as mechanics:

1. **Where D-09 is computed.** FR-07's availability badge (`0` / `1–5` / `>5`) is
   derived, never stored (ADR-003: `stock_quantity` is the single stored truth).
   `docs/constitution.md` C8 and AGENTS.md §3 are absolute in wording: *business
   logic lives only in service classes*. ADR-003 phrased the same rule as "a
   derived enum in `BookResponse`", and every enum already in this repository
   (`UserStatus`, `RefreshTokenStatus`, `UserRole`, `ConfirmOutcome`) is
   constants-only, with all rules in `*/service/`. Left to the mapper as a
   default method, the thresholds would have been the first rule implemented
   outside the service layer and the second place anyone could restock a badge
   differently.
2. **What a response record may carry.** Plan §2's `BookResponse` example lists
   `id, title, author, isbn, price, coverUrl, availability, category`. The same
   plan returns `BookResponse` from `GET /books/batch?ids=` (line 101) as the
   feed for cart enrichment, whose JSON shows `"stockQuantity": 12` (line 174),
   and FR-10/LC-12 require the add-to-cart control to be bounded by `1..stock`.
   The example therefore cannot be the complete contract: either the batch
   endpoint invents a second DTO, or the list shape carries the quantity.
3. **How much of a mapper exists before it has a caller.** Catalog's first mappers
   have no endpoints yet (CA-06..CA-11 arrive later), so "obvious" additions like
   collection overloads would ship unconsumed, against C1 (YAGNI).

## Decision

### D-09 lives in the service layer; the mapper only binds it

- `book/api/Availability.java` is a **constants-only** enum (`OUT_OF_STOCK`,
  `LOW_STOCK`, `IN_STOCK`), documented with the thresholds and living in `api`
  because it is a published response value, not a column (precedent:
  `user/api/ConfirmOutcome.java` in auth-service).
- `book/service/AvailabilityPolicy.java` is the **single owner** of the rule: a
  stateless `@Component` exposing `Availability forStock(int stockQuantity)`,
  annotated `@Named("availabilityOfStock")` so a mapper can bind it by name.
  Negative quantities throw `IllegalArgumentException` rather than being
  laundered into `OUT_OF_STOCK`: `ck_books_stock` (ADR-003) makes the case
  unreachable from the database, so it is a caller bug.
- `book/mapping/BookMapper.java` holds **no rule of its own**. `availability` is
  mapped `source = "stockQuantity"` with `qualifiedByName`, so the badge is
  provably the policy's answer about the same quantity the response publishes.
- `plan.md` §2 and D-09 are updated in place to match: the badge is computed by
  the policy, not by the enum.

### Mapper conventions (binding for every later mapper)

- `componentModel = SPRING`, `injectionStrategy = CONSTRUCTOR`: mappers are
  injected beans, and a `uses` collaborator can never be absent at runtime.
- `unmappedTargetPolicy = ReportingPolicy.ERROR`: the record's component list is
  a compile-time obligation. A new component fails the build until it is wired,
  which is what actually keeps `created_at`/`updated_at` from leaking (C9) —
  not a reflection assertion that would only notice after the fact.
- **One owner per shape.** `CategoryMapper` owns `{ id, name }`; `BookMapper`
  reaches it via `uses = { CategoryMapper.class, AvailabilityPolicy.class }`
  instead of restating the nested mapping.
- **No unused overloads.** Only `toResponse(entity)` exists today; list/page
  mapping is added by the task that consumes it (CA-06, CA-10, CA-11).
- Tests stay plain JUnit with the generated `*Impl` classes and real seed
  fixtures — no Spring context (plan §8, pyramid level 1).

### The published read shape

- `BookResponse` = `id, title, author, isbn, price, coverUrl, availability,
  stockQuantity, category` (plan §2 order, plus `stockQuantity`).
  `CategoryResponse` = `id, name`.
- `stockQuantity` is published **deliberately**, on a read model that C22 makes
  anonymously readable: FR-10/LC-12 need the exact upper bound client-side, and
  D-10's cart re-validation consumes the same field through `GET /books/batch`
  rather than a second, drifting batch DTO. ADR-005 designs that batch endpoint
  on this record; if the review verdict is that exact inventory counts are too
  sensitive to publish, the fix is to split the batch DTO there — not to remove
  the field silently from the list shape.
- Money stays an exact `BigDecimal` at scale 2, serialized as a JSON number
  (D-08, NFR-07); nothing beyond the components above crosses the boundary (C9).

### Amendment (CA-10): the category listing's surface

Delivered with `GET /categories`, on this ADR's authority over the catalog read
model (not a schema, security or dependency change, so no new ADR is triggered):

- **No `sort` parameter; the order is fixed name-ascending.** AGENTS.md §6's
  pagination line shows `?page=0&size=20&sort=field,asc` as the convention, but
  the sort whitelist (D-06: `title`/`price`) is a books rule — FR-09 asks for a
  browsable list, FR-06's "sortable by title and price" does not extend to
  categories, and inventing a category sort grammar would be speculative (C1).
  The fixed `name` ascending order exists because an unordered paged read is
  nondeterministic across windows; it is an implementation detail, not a new
  contract — a later spec that needs client-side category ordering adds the
  parameter and amends this section.
- **LC-11/LC-28 paging rules are duplicated, not shared.** `CategoryService`
  re-declares the default-20/max-100 constants rather than importing
  `BookService`'s: a category → book reference would be a sideways feature
  dependency (C7) between two features that merely happen to share AGENTS §6's
  numbers today. The duplication is accepted deliberately; if the numbers ever
  diverge or a third paged read arrives, the consolidation is a `common/`
  constants holder added by that refactor, not by editing either service in
  place.
- **Empty state**: the empty `categories` table is the FR-09/LC-31 200-with-
  `content: []` outcome of the shared `PageEnvelope`, with no endpoint-specific
  code — consistent with ADR-003 ("the empty state is a read-side outcome,
  never an error").

### Amendment (CA-11): the batch read's surface

Delivered with `GET /books/batch?ids=` (D-10), the catalog side of the
cart-enrichment contract, on this ADR's authority (the record is unchanged; no
schema, security or dependency decision is made here, so no new ADR is
triggered — ADR-005 owns the order-service's Feign client and enrichment logic
built on top of this endpoint):

- **A bare `[BookResponse]` array, not a `PageEnvelope`.** The list and category
  reads are *pages* a human browses, so they carry the §6 envelope's totals; the
  batch is a keyed lookup the cart consumes by `id`, which the plan §2 endpoint
  map already specifies as a raw array. Wrapping it would invent paging
  semantics (a "page" of what the caller already named) that D-10 never asked
  for. The elements are the identical `BookResponse` record, produced by the one
  `BookMapper.toResponseList` — so an enriched cart line cannot drift from the
  catalog card beside it.
- **An unknown id is absent, never a 404** — the deliberate inversion of
  `GET /books/{id}`'s contract. `getBook` must insist on the single row the
  client named (LC-28's asymmetry: parseable-but-missing is the one legitimate
  404); `getBooks` reports which of many ids exist, because the cart's defensive
  "this line's book vanished" state (LC-14) is *read from the gap* in the
  response. A 404 would make that unreachable state unrepresentable and let one
  dead line fail the whole enrichment read. Duplicated ids collapse to one entry
  each and order is not contractual, both free consequences of the single
  `WHERE id IN (...)` (`findAllById`) that also satisfies D-10's one-Feign-call
  rule.
- **Boundary rules ride the existing paths, unchanged.** A malformed id in the
  `ids` list is the shared 400 validation `ProblemDetail` (field `ids`) via
  Spring's own `String -> UUID` conversion (LC-28, C23) — a well-formed id that
  simply names no book stays an absent entry, not a rejection. The id count is
  capped at `MAX_SIZE` (100) by a parameter `@Size` so one batched read can
  never outgrow a browsed page and an extremely long query string is refused at
  the boundary (LC-28) — a *rejection*, not a clamp, because there is no way to
  clamp "which specific books" a cart asked for without silently dropping lines.
  That cap is also the reason the batch satisfies NFR-02's "all lists paginated"
  while returning a bare array: it is bounded by the same one-page ceiling rather
  than left unbounded, so no paginated envelope is needed to keep the read
  bounded. **Absent and blank both answer `[]`, never a 400**: an *absent* `?ids=`
  binds to `null`, and a *present-but-blank* `ids=` collapses (via Spring's single-
  value handling and the comma-dropping `StringToCollectionConverter`) to an empty
  `List`, so neither is the "required" error a scalar `@RequestParam` would raise —
  FR-11's empty cart is an empty array either way. The `isEmpty()` short-circuit is
  therefore genuinely HTTP-reachable (the blank path) *and* avoids the case Spring
  Data leaves undefined (`findAllById` over an empty iterable). A *non-blank*
  unparseable id, by contrast, is a real `String -> UUID` failure and so a 400 —
  the malformed/empty asymmetry is exactly LC-28's, and both blank- and
  malformed-`ids` cases are pinned by slice tests. The route inherits
  `GET /api/v1/books/**` on the public allowlist (C22, ADR-008) with no matcher
  change — it is a catalog read like every other GET here.

## Consequences

- C8 holds literally: the only rule CA-05 introduced is in a service class, and
  the enum stays in the codebase's constants-only style.
- CA-06..CA-09 can map pages, search results and details by injecting
  `BookMapper` — availability comes right for free, and cannot disagree with
  the quantity beside it. `AvailabilityPolicy` is also the seam the future
  checkout spec re-uses if a stock change must re-evaluate a badge.
- Adding a field to `BookResponse`/`CategoryResponse` is now a compile-breaking
  change: it forces an explicit `@Mapping`, which keeps the frontend's
  plan §2 contract and the DTO in lockstep.
- The public `stockQuantity` is a documented exposure, so a later "hide exact
  stock" decision is an ADR amendment (likely ADR-005), not a code archaeology
  problem.
- Auth-service keeps its hand-built responses (its records have no entity to
  map from); this ADR binds mappers that exist, and does not force a retrofit.
- No dependency is added: MapStruct was already declared by CA-02's scaffold, so
  §12.4's dependency trigger is not re-triggered here.

## References

- Spec 001: FR-06..FR-09, FR-10/LC-12, FR-11/LC-30, FR-14; NFR-05, NFR-07;
  plan §2 (JSON contract), §5 D-08/D-09/D-10, §6.1 (`availability_thresholds`)
- `docs/constitution.md`: C1, C5, C8, C9, C11, C14, C22
- AGENTS.md §3 (package-by-feature, MapStruct), §6 (records in/out), §8 (pyramid)
- ADR-003 (`stock_quantity` as the only stored truth; superseded in wording only,
  on where the badge is computed); ADR-005 (upstream consumer of `BookResponse`
  through `GET /books/batch`)
- Code: `catalog/catalog/book/api/{Availability,BookResponse}.java`,
  `book/service/AvailabilityPolicy.java`, `book/mapping/BookMapper.java`,
  `category/api/CategoryResponse.java`, `category/mapping/CategoryMapper.java`,
  and (CA-10) `category/api/CategoryController.java`,
  `category/service/CategoryService(-Impl).java`
