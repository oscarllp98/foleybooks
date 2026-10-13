# ADR-007: Public auth allowlist, fail-closed metrics, and ProblemDetail security errors

## Status

Accepted — amended for AU-17 (login, 2026-09-21): the body-credential pattern is
extended to login's credential check and login's timing-parity decision is recorded.

## Context

AU-04 (spec 001 plan D-14) requires the auth-service `SecurityConfig` to permit
exactly the endpoints the spec makes unauthenticated and deny the rest (C22).
Reviewing the task against the governing documents surfaced three conflicts:

1. **Allowlist divergence.** The spec mandates that `/auth/refresh` work with an
   expired access token (FR-04 — refreshing is the normal case, not an
   exception) and that `/auth/logout` work with **no** valid access token at all
   (FR-05, D-05, LC-21). The refresh credential itself authenticates these two
   calls, so the HTTP layer must treat them as public. However, the public
   allowlist in `constitution.md` C22 listed only "auth login/register, email
   confirmation/resend, catalog GETs, health/info", and AGENTS.md §5 omitted
   confirm/resend/refresh/logout and the JWKS endpoint entirely. The plan §2
   endpoint map marked refresh and logout `public` while simultaneously claiming
   to "match the constitutional allowlist exactly" — an internal contradiction.

2. **`/actuator/metrics` in auth-service.** AGENTS.md §5 requires actuator
   `metrics` to be exposed but restricted to `ROLE_ADMIN`. Enforcing a
   role-based decision requires validating a JWT, but AGENTS.md §2 assigns JWT
   validation to `spring-boot-starter-oauth2-resource-server` in the *other*
   services only — auth-service issues tokens and has no validating decoder.
   Additionally, AU-04 precedes AU-15, so no RSA key material exists yet even
   for a self-issued decoder.

3. **Security-filter denials bypass the AU-03 advice.** The error contract
   (AGENTS.md §6, plan §2, NFR-06, D-15) says 401/403 are ProblemDetails with a
   `traceId`, but `@RestControllerAdvice` only sees exceptions raised inside
   `DispatcherServlet`; deny-by-default rejections are raised by Spring Security
   `AuthorizationFilter` and rendered by the configured
   `AuthenticationEntryPoint` / `AccessDeniedHandler` — bare statuses unless
   wired explicitly.

## Decision

- **Amend the allowlist wording** (user-approved) in `constitution.md` C22,
  AGENTS.md §5 and plan §2 to the spec-mandated set: auth
  register/confirm/resend/login/refresh/logout, `GET /oauth2/jwks`, catalog
  GETs, `health`/`info` — clarifying that "public" means *no valid access token
  required*, because refresh and logout authenticate with their own opaque
  refresh token in the body.
- **Login joins the body-credential trio (AU-17).** `POST /api/v1/auth/login`
  authenticates at the service layer too: `TokenServiceImpl.login` matches the
  email/password pair from the request body against the BCrypt-12 hash and the
  `UNVERIFIED` status gate. That is how C26 is satisfied for auth-service
  specifically — the service issues tokens and, per AGENTS.md §2, has no
  validating resource-server decoder, so Spring Security cannot decide its
  credentials the way it does for protected resources elsewhere. The filter
  chain still owns the allowlist and every denial it renders; the three auth
  commands authenticate with exactly the credential the body carries
  (email/password, refresh token — FR-03..FR-05, D-05).
- **Login timing parity (AU-17).** The unknown-email branch of login verifies
  the presented password against a lazily minted throwaway BCrypt hash, so
  response time cannot distinguish "no such account" from "wrong password" —
  FR-03's "never reveal … whether the account exists" is read to cover the
  timing channel, as FR-02's resend already covers timing-of-write (NFR-01;
  the throwaway hash and every credential stay out of logs and responses,
  C24). Approved by the user when AU-17 was reviewed.
- **auth-service SecurityConfig**: `permitAll` on exactly those paths (under the
  `/api/v1` prefix the gateway forwards without stripping, plus `/oauth2/jwks`
  and the actuator health/info), `/actuator/metrics/**` guarded by
  `hasRole("ADMIN")`, `anyRequest().denyAll()`, no CORS (AGENTS.md §5), and
  springdoc paths (`/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`)
  permitted on the **dev profile only** (AGENTS.md §6).
- **Fail-closed metrics**: keep the `ROLE_ADMIN` matcher even though no token
  validation exists in auth-service yet — the rule is enforced against nobody,
  so `metrics` is unreachable (401/403) until auth-service gains a decoder or a
  later spec revisits actuator access. No HTTP Basic, no extra dependency and
  no new secret is introduced for an endpoint nothing scrapes yet.
- **Authorization proofs use `.user()` post-processors in auth-service tests.**
  AGENTS.md §5 prescribes `spring-security-test` `.jwt()` post-processors, but the
  `jwt()` processor needs `spring-security-oauth2-jose` on the classpath — a
  dependency auth-service deliberately does not have (AGENTS.md §2: it issues, the
  other services validate). `.user(...).roles(...)` keeps the security filter chain
  fully active (C25) without adding a test-scope dependency. Catalog-service and
  order-service tests use `.jwt()` as usual, since they are resource servers.
- **ProblemDetail at the security boundary**: implement an
  `AuthenticationEntryPoint` and `AccessDeniedHandler` in auth-service that
  write the same ProblemDetail shape as `GlobalExceptionHandler` —
  `urn:foley-books:problem:unauthenticated` (401) and
  `urn:foley-books:problem:forbidden` (403) with `instance` and `traceId`
  (from the AU-03 `TraceIdFilter` MDC). The pattern is copied to
  catalog-service and order-service (AU-04 successors CA-02/OR-03) where JWT
  validation does produce real 401/403 traffic.

## Consequences

- The constitutional text now matches the spec's product truth; C22's deny-by-
  default posture is preserved because the amendment only clarifies the
  explicit allowlist, never a wildcard.
- Login can never be turned into an account-enumeration oracle by response
  *content* (unknown email and wrong password render the identical 401 Problem
  Detail, LC-06) nor by response *time* (both pay one BCrypt verification) — the
  same guarantee AU-20's `EnumerationSafetyIT` checks for register and resend.
- The 403 for an UNVERIFIED account is the one intentional distinction on the
  login path, and it is gated behind a verified password, so only the real
  owner can ever see it (LC-05).
- `GET /actuator/metrics` on auth-service returns 401/403 for every caller;
  when auth-service eventually self-validates tokens, the matcher starts
  working without a config change.
- Every 401/403 in the system — filter-chain or controller-originated — now
  carries a `traceId` correlatable with logs (NFR-06), satisfying AGENTS.md §6
  uniformly.
- Dev-profile Swagger UI is reachable directly on the service port (never via
  the gateway, which routes only `/api/v1/**`); docker/prod profiles continue
  to deny it.
- The AU-03 URN prefix typo (`urn:foleybooks:`) is corrected to the
  documented `urn:foley-books:` namespace as part of this change.
