# ADR-011: Frontend token storage and the 401 refresh interceptor

## Status

Accepted — records the decisions FE-01 delivered for `frontend/src/lib/`
(spec 001, D-13): where the two client-side credentials live, how a 401 is
recovered from, and how a lost session ends. Binding for FE-03 (api/ clients
must reuse the instance), FE-04 (AuthContext must wrap this store, not
parallel it) and FE-10 (router must wire the session-expired handler).
Amended by ADR-012, which settles how FE-04 wraps the store (observability,
identity snapshot, handler chaining) without changing the decisions below.

## Context

FR-03/FR-04 give the client a 15-minute access JWT and a 7-day rotating
refresh credential, and user story 4 promises "my login persists up to 7
days" — across page reloads, with no server-side session (AGENTS.md §4:
stateless services). D-13 fixes the shape of the client layer (single axios
instance, 401 → refresh interceptor with a **single-flight queue**, access
token in memory) but leaves three questions FE-01 had to settle:

1. **Where the refresh credential survives a reload.** The access token is
   explicitly memory-only; the spec never says how the refresh token persists,
   yet without persistence every browser refresh would end the session and
   story 4 would fail.
2. **The scope of LC-22's serialization.** "Concurrent refreshes from multiple
   tabs/clients → clients serialize refresh calls" — a per-tab queue cannot
   serialize across tabs, and the spec itself appends "(documented tradeoff)".
3. **What "redirect on refresh failure" means before a router exists.** FE-01
   precedes FE-07..FE-10, so no login route or client-side navigation exists
   yet, and `lib/` must not import React Router (C10 layering: components and
   routing never reach into the HTTP layer, and vice versa).

## Decision

- **Access token: memory only.** Held in a module-scoped variable in
  `src/lib/tokens.ts`; never written to any Web Storage. This is D-13's "in
  memory via AuthContext" store relocated to `lib/` because FE-04 has not been
  built yet — FE-04 must expose this same store through `useAuth` rather than
  keeping a second copy.
- **Refresh token: `localStorage` under `foleybooks.refreshToken`.** Rotated
  on every successful refresh, removed with the access token on logout/reuse.
  Alternatives considered and rejected:
  - *httpOnly cookie*: would require auth-service to set and the gateway to
    relay cookies, breaking the opaque-token-in-body contract (FR-05, D-05,
    ADR-007's "refresh and logout carry their own opaque refresh token in the
    body"), CORS credentialed requests, and the "identity travels in JWT
    claims only" rule (AGENTS.md §4).
  - *sessionStorage*: survives reload but not a new tab or browser restart, so
    it would deliver a per-tab session, not the 7-day session of story 4,
    while still being readable by injected scripts — the downside of
    localStorage without its benefit.
  - *memory-only*: fails user story 4 outright.
- **Accepted risk.** A stored refresh token is readable by any XSS payload.
  The mitigation is server-side, not client-side: single-use rotation with
  reuse detection (FR-04/LC-08) means a stolen credential that gets used
  revokes every session of the account and forces re-login; if the thief
  refreshes first, the legitimate tab's next refresh fails and the 401 flow
  ends the session visibly. XSS prevention proper (CSP) is out
  of MVP scope.
- **LC-22: single-flight per tab; cross-tab races ride the documented
  tradeoff.** Concurrent 401s in one tab share one in-flight `/auth/refresh`
  promise, so a tab can never lose the rotation race against itself. Two tabs
  refreshing simultaneously can still race; the loser is detected as reuse and
  all sessions die, which the interceptor turns into "clear tokens + redirect
  to login" — precisely the outcome LC-22 documents as the tradeoff. A
  Web Locks / BroadcastChannel mutex was rejected under C1/C3 (YAGNI): D-13
  asks for a single-flight queue, and the failure mode it would prevent is
  spec-sanctioned. Revisit here if a future spec requires true multi-tab
  session coherence.
- **Interceptor contract (`src/lib/http.ts`).** On 401: retry once after a
  successful refresh, unless the request is itself an `/auth/**` call (so a
  failed login never triggers a refresh), already a retry, or no refresh token
  is present (anonymous 401s propagate untouched). On refresh failure:
  `clearTokens()` + session-expired handling — the handler injected via
  `setSessionExpiredHandler` when one is wired (FE-10's router navigate to
  `/login` with the session-expired message, LC-07), else a hard
  `window.location.assign('/login?reason=session-expired')` as the
  pre-router fallback.
- **The axios instance lives in `lib/`, but only `api/` calls it.** C10
  ("only the api/ layer performs HTTP calls") is preserved: `lib/http.ts` is
  transport plumbing with no endpoints; the typed clients FE-03 builds in
  `src/api/` import this single instance, and components keep fetching only
  through TanStack Query → api/.

## Consequences

- FE-04 replaces any planned in-context token state with `tokens.ts`
  (`getAccessToken`/`setTokens`/`clearTokens`) and wires
  `setSessionExpiredHandler` on logout, keeping one in-memory truth.
- FE-10 should call `setSessionExpiredHandler` at router mount so LC-07's
  redirect is client-side navigation carrying a return path, and read the
  `reason=session-expired` search param of the fallback URL.
- `VITE_API_BASE_URL` is a Vite variable and therefore documented in
  `frontend/.env.example` (Vite reads env files from the frontend project
  root); the root `.env.example` points there, a deliberate deviation from
  AGENTS.md §3's single-file layout.
- No token or password value is logged anywhere (C24); interceptor tests use
  fixed fixtures only.
- If a later spec demands multi-tab refresh serialization, it must amend this
  ADR before a Web Locks mutex is added.

## References

- Spec 001: FR-03, FR-04, FR-05; user story 4; LC-05, LC-07, LC-08, LC-22,
  LC-27; plan §1 (`lib/`), §2 (auth endpoint map), §5 D-13, §6.5 (refresh
  interceptor test)
- AGENTS.md §4 (stateless services, identity in JWT claims), §5 (token TTLs,
  rotation, opaque refresh tokens), §2 (axios interceptors), §3 (layout)
- `docs/constitution.md`: C1, C3, C10, C24, C27
- ADR-007 (refresh/logout carry the token in the body), ADR-008 (resource
  servers validate via JWKS; expiry answers 401, which this layer reacts to)
