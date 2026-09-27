# ADR-012: AuthContext session state — observable token store and identity snapshot

## Status

Accepted — amends ADR-011 for the FE-04 scope (spec 001, FR-03..FR-05): how
`AuthContext`/`useAuth` consume the `lib/tokens.ts` store, what extra piece of
session state is persisted, and how the single session-expired handler slot is
composed between FE-04 and FE-10. Binding for FE-09 (header renders from
`useAuth`), FE-10 (router guard reads `isAuthenticated`/`sessionExpired` and
installs its navigation handler) and FE-14/FE-15 (anonymous-vs-authenticated
UI decisions read the same context).

## Context

ADR-011 declared `tokens.ts` the one token store and required FE-04 to
"expose this same store through `useAuth` rather than keeping a second copy".
Building the context surfaced three questions ADR-011 left open:

1. **Reactivity without duplication.** The 401 interceptor rotates tokens
   inside `lib/`, outside React's state system. If the context kept its own
   `useState` copy of the tokens it would be the second copy ADR-011 forbids;
   if it only read the store on render, a transparent rotation would never
   re-render consumers.
2. **Whose identity is the UI allowed to show after a reload.**
   `TokenPair.user` (`{id, email, role}`) arrives with login and with every
   refresh. A reload kills the in-memory access token, so the context would
   have nothing to render for the header/guards. Restoring identity by
   force-refreshing at boot would rotate the single-use credential on every
   page load — and two tabs booting together would race straight into
   FR-04/LC-08 reuse detection, revoking the whole account.
3. **Who owns the `setSessionExpiredHandler` slot.** ADR-011 says FE-04 wires
   it and also that FE-10 calls it at router mount; the slot is single, so
   whoever installs last silently drops the other.

## Decision

- **`tokens.ts` becomes an observable store; React subscribes.**
  `subscribeTokenChanges(listener)` + `getTokenSnapshot()` (a cached,
  immutable `{accessToken, refreshToken, user, sessionExpired}` snapshot,
  replaced only on store mutations) make the module a valid
  `useSyncExternalStore` source. `AuthProvider` derives its entire context
  value from that snapshot — no parallel token state anywhere. The store
  itself stays React-free; `lib/` still imports no `features/` code.
- **Identity snapshot persisted under `foleybooks.sessionUser`.**
  `setTokens` also stores the `UserSummary` returned by login/refresh, so
  `isAuthenticated`, `user` and the guard state survive a reload without any
  credential rotation at boot. Accepted risk, consistent with ADR-011's own
  tradeoff: the stored value (UUID, email, role) is strictly less sensitive
  than the refresh token already living in `localStorage` beside it — the
  same XSS perimeter is affected, and CSP remains out of MVP scope. The
  object is re-validated (`isUserSummary`) on read, so a hand-edited value
  degrades to `null`, never to forged role claims that matter (the server
  still decides every `ROLE_ADMIN` question from JWT claims — AGENTS.md §5,
  ADR-008).
- **`isAuthenticated` means "a live refresh credential exists", not "an
  access token is in memory".** After a reload the session is still 7-day
  valid (user story 4); the interceptor restores the access token on the
  first 401 (LC-07/LC-22). Guards and the header must not treat the
  post-reload window as logged out.
- **Expiry is a store flag, logout is not: `markSessionExpired()`.**
  `sessionExpired` is set only when the refresh credential died (LC-07),
  cleared by the next `setTokens`, and deliberately survives `clearTokens()`
  — an explicit logout (FR-05) and an expiry (LC-07) look identical
  token-wise but must read differently on the login page. FE-10 may also
  mirror the `?reason=session-expired` fallback param per ADR-011.
- **The rotation door stays single: `refreshSession()` is exported.**
  The context's `refresh()` and the interceptor's retry path share the one
  single-flight `refreshSession()`; session teardown on failed rotation runs
  from that single failure point (`performRefresh` → `onSessionExpired`), so
  no caller can clear tokens or navigate without the other path noticing.
- **`setSessionExpiredHandler` returns the handler it displaces; handlers
  compose by chaining.** `AuthProvider` wraps whatever was installed before
  it (in practice: nothing, or FE-10's router if it mounts first — child
  effects run before the parent's) and restores it on unmount. FE-10 gets
  the previous handler back from its own install call and chains to it.
  The slot stays single as ADR-011 mandated; only composition is added.
- **`login` returns a `LoginResult`, not a thrown error.** `{ok:true,user}`
  / `{ok:false,reason:'invalid-credentials'|'unverified'}` classified by
  HTTP status alone (401/403) — both flavors are already enumeration-safe
  server-side, and the form gets a typed branch for LC-05/LC-06 messaging.
  Anything without a response (network) still throws.

## Consequences

- FE-04 ships: `lib/tokens.ts` (observable store), `lib/http.ts` (chaining
  seam + exported `refreshSession`), `features/auth/AuthContext.ts`
  (context + value contract), `features/auth/AuthProvider.tsx` (the store
  wrapper), `hooks/useAuth.ts` (consumer door).
- FE-09/FE-10/FE-14/FE-15 read auth state exclusively through `useAuth()`;
  they must never touch `lib/tokens.ts` or `lib/http.ts` directly.
- FE-10 installs its navigation handler via
  `setSessionExpiredHandler(navigateToLogin)`, chains the returned previous
  handler, and may read `sessionExpired` from the store snapshot (or the
  `reason` search param after the pre-router hard redirect).
- With `AuthProvider` mounted the handler slot is never empty, so ADR-011's
  `window.location.assign('/login?reason=session-expired')` fallback stays
  reachable only where no provider is mounted (unit tests). Between FE-04
  and FE-10 a session expiry settles state without navigating: no redirect
  and no message exist yet — the user-visible LC-07 redirect lands with
  FE-10, as planned.
- The persisted `sessionUser` key joins `foleybooks.refreshToken` as a
  store-owned `localStorage` item; nothing else may write these keys.
- No new dependency was added (C2); C10's "only api/ calls HTTP" is
  untouched — the context calls `api/auth`, and `refresh()` delegates to
  the interceptor's internal path, which was already part of `lib/`'s job.

## References

- Spec 001: FR-03, FR-04, FR-05; user story 4; LC-05, LC-06, LC-07, LC-08,
  LC-09, LC-21, LC-22; plan §1 (`features/auth/AuthContext`, `hooks/useAuth`),
  §5 D-13
- AGENTS.md §4 (stateless services, identity in JWT claims), §5 (rotation,
  reuse detection), §2 (frontend auth/refresh interceptors)
- `docs/constitution.md`: C1, C2, C3, C10, C11, C24
- ADR-007 (refresh/logout carry the opaque token in the body), ADR-011
  (token storage + single-flight interceptor; this ADR amends its FE-04
  consequences, not its storage decisions)
