# ADR-009: catalog read-model mapping conventions and the published book shape

## Status

Accepted — documents the mapping pattern delivered by CA-05 (spec 001, Phase 2) and
resolves two questions AGENTS.md §3/§6 and the plan leave open: where a derived
response field is computed, and which read-model conventions every later mapper
must follow. Binding for CA-06..CA-11 (catalog reads and `GET /books/batch`) and
for OR-05 (the Feign DTOs that consume them). ADR-004 and ADR-005 remain reserved
by plan §5 for the cart schema and the batch-enrichment decision.

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
  `category/api/CategoryResponse.java`, `category/mapping/CategoryMapper.java`
