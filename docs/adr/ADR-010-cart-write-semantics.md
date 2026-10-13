# ADR-010: Cart write semantics — PATCH branches, line-not-found 404, identity binding

## Status

Accepted — records the contract decisions OR-08 delivered for
`PATCH /api/v1/cart/items/{bookId}` (FR-12, with `POST /cart/items` composition
for FR-10 and the `GET /cart` read exposure). It clarifies one sentence of
ADR-005, completes plan §2's abbreviated PATCH row, and states the identity
binding rule for the cart controller. No schema change (ADR-004), no transport
change (ADR-005), no security-layer change (ADR-007/ADR-008), no new
dependency (AGENTS.md §2).

## Context

FR-12 defines three behaviors — "changes a line's quantity within 1..stock",
"setting quantity to 0 explicitly removes the line", "quantity above stock →
rejected with the available stock shown" (LC-12, LC-16) — and plan §2 promises
`UpdateQuantityRequest → 200 CartResponse / 422`. Three states the spec leaves
open had to be decided before the code could ship, and OR-09/OR-10/OR-11 and
FE-15 will now build on them:

1. **A positive quantity naming no line.** The PATCH row lists no 404, while
   the POST row lists one explicitly — abbreviation or intent? The plan table
   abbreviates standard statuses on every row (no 400/401 anywhere), so the
   row cannot settle it.
2. **Whether the zero removal must consult the catalog.** ADR-005 says
   "Add/patch reuse the single lookup … OR-08 re-validates the target line's
   stock before writing". Read literally, even a delete would require
   `findBook` — and a cart line whose book vanished (LC-14, a defensive state
   FR-11 promises the user can act on: "must update or remove the line")
   would become unremovable through this verb: the lookup would answer
   `book-not-found` before the delete ever ran.
3. **What the handler does with an authenticated token whose `sub` is not a
   UUID.** C26 makes authentication and authorization verdicts Spring
   Security's alone; the handler must bind the owner without deciding who is
   authenticated.

ADR-004 pre-decides part of (2): `ck_cart_items_quantity CHECK (quantity >= 1)`
means set-to-zero is a delete, never a stored zero, and FR-13's idempotence
("removing a non-existent line → success") is the cart tree's stated
removal-semantics precedent.

## Decision

1. **PATCH branches, in this order.**
   - `quantity == 0` → **delete the line if present, with no east-west
     lookup.** A removal asserts no stock bound (zero cannot exceed anything)
     and no live product; skipping the lookup is what keeps an LC-14 line
     whose book vanished clearable through FR-12's own verb. An absent cart or
     line is the requested state already: idempotent success, 200 with the
     current read view (FR-13's rule in the zero route).
   - `quantity >= 1` → **local line-existence check first** (one short
     transaction unit; no network call for a line that was never there), then
     the live `findBook` bound, then the write. Above stock — or a
     defensively-reached negative, the mirror of add's re-check — is 422
     `insufficient-stock` carrying `availableStock` (LC-12, LC-16) with
     nothing written; equality (`quantity == stock`) passes, the LC-30
     boundary where a line is still sufficient. A line whose book has
     vanished propagates `book-not-found`.
   - The quantity is **set**, never summed: naming the resulting state twice
     cannot drift it; summing is FR-10's POST semantics exclusively.
   - The 200 body is the full `CartResponse` (plan §2), i.e. the change
     composes into the FR-11 read — totals recalculate live and the client
     never refetches.
2. **A positive quantity naming no line answers 404
   `urn:foley-books:problem:cart-line-not-found`**, echoing the requested
   `bookId` in the plan §2 extra-property shape (`availableStock`,
   `resendHint`, `bookId`). FR-12 changes "a line's" quantity and never
   creates one; AGENTS.md §6's standard status for missing is 404. Rejected
   alternatives: upserting (duplicate creation semantics for POST's job, and
   it would silently resurrect a line the user's other session just deleted)
   and a silent no-op 200 (hides a stale-state client bug behind an answer
   that changed nothing it could have changed). **The line-not-found answer
   precedes the stock gate**: a doubly-invalid request (no line, quantity
   above stock) is 404, because the missing *resource* is a truer statement
   than the moot bound — and it is answered locally, so it never depends on
   catalog's availability (ADR-005's absence rule). The two 404s stay
   distinct URNs: `cart-line-not-found` names a missing line,
   `book-not-found` a missing book behind an existing line; the cart is
   private per-user state, so echoing the id leaks nothing public (C22).
3. **Identity binding is not an authentication decision (C26).**
   `CartController` reads the owner as `UUID.fromString(jwt.getSubject())`
   and adds no verdict: the chain and `@PreAuthorize("isAuthenticated()")`
   (the shape the OR-03 SecurityConfig probe pinned for OR-06..OR-09) own
   every 401/403, rendered by `ProblemDetailResponder` per ADR-007. A
   well-signed token whose `sub` is not a UUID cannot come from a client —
   RS256 + JWKS (ADR-008) and `JwtIssuer`'s UUID-only subjects make it an
   issuer-side contract violation — so it surfaces as the advice's generic
   500 with `traceId` (NFR-06) rather than an application-made 401, and no
   cart lookup runs under a half-known identity (NFR-01).
4. **POST stays the composed 201 it is contracted to be.** The handler
   delegates `add` then `read` and answers 201 with the `CartResponse` plus
   `Location: /api/v1/cart/items/{bookId}` — plan §4's `cart.add … return
   cart.read(userId)` made concrete without changing OR-06's `CartLine`
   service contract; a failed add never reaches the read. C8's thinness
   holds: two delegations and a status, no rule.

## Consequences

- **ADR-005's "re-validates the target line's stock before writing" reads as
  positive writes only** — this ADR is the record that a deletion carries no
  stock bound and must stay catalog-free, precisely so LC-14/LC-30 lines
  remain actionable through FR-12 itself.
- **Plan §2's PATCH row is completed** (updated in the spec workspace to
  `→ 200 CartResponse / 404 / 422`); future rows are read as abbreviation of
  the standard statuses, not as exhaustive lists.
- **OR-09's DELETE inherits the posture**: local-first, catalog-free,
  idempotent 204 — it must not introduce a third not-found semantics.
- **OR-10's web matrix and OR-11's `CartFlowIT` must keep the two 404s
  distinct** and assert the 404-before-422 precedence; the malformed-sub 500
  case is pinned by `CartControllerTest`.
- **FE-15** can rely on: zero-removal always succeeding (flagged lines
  included), the 422 carrying `availableStock` to render, and a 404 on a
  vanished line meaning "refresh your stale view", not "you hit a bug".
  The frontend's reading of this decision (delivered with FE-15): the
  stepper's decrease-to-zero activation routes to **DELETE /cart/items/{bookId}
  (FR-13's verb, idempotent 204)** rather than `PATCH {quantity: 0}` — the
  two are equivalent per this ADR, DELETE is the verb that names the intent,
  and its 204 carries no body, so the page refetches the server's fresh view
  instead of trusting a locally computed one.
- **Tests (plan §6).** `cart_update_aboveStock_rejectedWithAvailableStock`
  (LC-12/LC-16), `update_whenQuantityZero_removesTheLineAndAnswersTheEmptiedCart`
  and
  `update_whenPositiveQuantityAddressesNoLine_rejectsBeforeAnyCatalogCall`
  (branch order + precedence), `update_whenLineVanishesBetweenChecks_answersLineNotFoundInsteadOfResurrectingIt`
  (the re-load inside the write unit), and the `CartControllerTest` wire
  tests pin this decision; none of it needed a schema, dependency, or
  security-config change.

## References

- Spec 001: FR-10..FR-13; LC-12, LC-13, LC-14, LC-16, LC-27, LC-30; NFR-01,
  NFR-06, NFR-07
- Plan 001: §2 (endpoint map, ProblemDetail extra-property shapes), §4
  (`cart.add` / `cart.read` pseudocode), §6.1/§6.2 (unit and web tests)
- `docs/constitution.md`: C4, C6, C8, C9, C22, C23, C25, C26
- AGENTS.md §5 (deny by default, method security), §6 (records in/out, 404
  missing, 422 business rule, consistent URNs, UUID identifiers)
- ADR-004 (`ck_cart_items_quantity`: zero is a delete; the cart row
  survives); ADR-005 (the two-lookup split, absence vs unavailability —
  whose "before writing" clause this ADR narrows); ADR-007 (security
  rejections render as ProblemDetail); ADR-008 (the resource server owns
  the authentication verdict)
