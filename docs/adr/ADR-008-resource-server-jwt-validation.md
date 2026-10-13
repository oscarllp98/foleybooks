# ADR-008: Resource-server JWT validation and roles-claim mapping

## Status

Accepted — documents the security pattern delivered by CA-02 (catalog-service
scaffold, spec 001). Binding for order-service (OR-03), which copies the same
shape verbatim.

## Context

AGENTS.md §2 mandates that every non-auth service validates JWTs with
`spring-boot-starter-oauth2-resource-server`. CA-02 introduced that starter into
the build graph for the first time and, in doing so, resolved three questions the
governing documents leave open:

1. **Authority mapping.** AGENTS.md §5 fixes the claim set (`sub`, `email`,
   `roles`, …) and §5/§2 require `metrics` and future admin operations to speak
   `hasRole('ADMIN')`, but the token's `roles` claim carries plain names —
   auth-service's `JwtIssuer` writes `List.of(user.getRole().name())`, e.g.
   `["ADMIN"]` — while the resource server's built-in converter reads the `scope`
   claim and prefixes `SCOPE_`. Without a mapping decision, `hasRole("ADMIN")` on
   `/actuator/metrics` is unreachable for every caller, including admins — a
   silent fail-closed trap rather than a working rule.
2. **Bearer rejections at the filter boundary.** ADR-007 established that
   filter-chain rejections render as ProblemDetail with a `traceId`, and named
   CA-02/OR-03 as the successors where that traffic becomes real (auth-service
   had no decoder at all, so its entry point only ever saw authorization
   denials). A failed bearer token is rejected by the resource-server filter
   before `ExceptionHandling`'s entry point applies, so its own entry point has
   to be wired explicitly.
3. **The shape of "catalog GETs" in C22.** The MVP public surface is
   `GET /books`, `/books/{id}`, `/books/batch`, `/categories` (plan §2), but
   `{id}` is open-ended, so the allowlist cannot be spelled as literal paths.
   ADR-007's amendment insists the allowlist is explicit and "never a wildcard";
   the GET matcher is pattern-based by necessity, so the boundary it does and
   does not draw must be recorded.

## Decision

- **JWKS via env.** `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` is
  bound to `${JWKS_URI:…}`: default `http://localhost:8081/oauth2/jwks` (host-run
  dev), overridden per profile to `http://auth-service:8081/oauth2/jwks` in
  docker, and always overridable from the environment (AGENTS.md §5,
  `.env.example` "JWT"). The `iss` claim is not validated — key-set trust is the
  contract (documented in `.env.example`).
- **`roles` → `ROLE_` authorities.** Publish a
  `JwtGrantedAuthoritiesConverter` configured with `authoritiesClaimName="roles"`
  and `authorityPrefix="ROLE_"`, wrapped in a `JwtAuthenticationConverter` bean
  and referenced explicitly from the filter chain. No hand-rolled
  `GrantedAuthority` code (C26): the mapping uses stock Spring Security
  converters, pinned by `JwtAuthenticationConverterTest`.
- **Metrics stays a filter-chain rule.** `/actuator/metrics/**` keeps
  `hasRole("ADMIN")`; `@EnableMethodSecurity` is deliberately **not** enabled in
  catalog yet because no annotated operation exists (AGENTS.md §5's method
  security arrives with OR-03's cart and the future admin spec). Catalog is the
  first service where the rule is enforced for real rather than fail-closed.
- **ProblemDetail on bearer failures.**
  `oauth2ResourceServer.authenticationEntryPoint(ProblemDetailResponder)` in
  addition to the `exceptionHandling` entry point/denied handler, so every 401/403
  in the service — anonymous denial, authorization denial, and unverifiable
  token — shares ADR-007's ProblemDetail contract (`urn:foley-books:problem:
  unauthenticated` / `…forbidden`, `instance`, `traceId`). The RFC 6750
  `WWW-Authenticate` header is sacrificed deliberately: ADR-007 already chose a
  uniform problem body over protocol hints, and the frontend contract is the
  gateway, not direct service traffic.
- **GET-wildcard rule (C22 boundary).** The allowlist is `HttpMethod.GET` on
  `/api/v1/books/**` and `/api/v1/categories/**` plus health/info; every other
  method and path hits `anyRequest().denyAll()`. The wildcard is load-bearing:
  any future GET nested under those trees that must **not** be public (an admin
  list, a stats endpoint) requires an explicit matcher change in
  `SecurityConfig` **before** the handler ships — the deny-by-default review gate
  is the matcher diff, not the handler annotation. A dedicated
  `SecurityConfigTest` case pins today's public-by-pattern behavior so the
  hazard is visible, not accidental. Admin writes of AGENTS.md §2's end state
  ("public read, admin write") will extend this same chain.

## Consequences

- CA-02's dependency addition is now on record (§12.4); no further library
  changes result from this ADR.
- order-service's SecurityConfig (OR-03) copies the converter wiring; a roles
  claim rename or split in auth-service's JwtIssuer is a contract break that
  must amend this ADR first.
- Expired/foreign tokens on public catalog GETs answer 401 (fail-closed) instead
  of degrading to anonymous — the client refreshes via `/auth/refresh` (FR-04).
- `metrics` is now genuinely ROLE_ADMIN-gated in the services that validate
  tokens; the ADR-007 fail-closed caveat remains auth-service-only.
- The GET-wildcard rule gives every later catalog endpoint an explicit security
  review step; `SecurityConfigTest.unmappedNestedGetUnderBooks_…` documents the
  current scope, and tightening it is a one-matcher edit.

## References

- Spec 001: plan §2 (endpoint map), §5 D-14; FR-06..09 (public read), NFR-01
- AGENTS.md §2 (resource-server mandate), §5 (claims, metrics role, JWKS relay),
  §6 (ProblemDetail, dev-only springdoc), §12.4 (ADR triggers)
- `docs/constitution.md`: C22, C23, C25, C26
- ADR-007 (ProblemDetail security errors; pattern source), ADR-003 (catalog MVP
  is seeded read-only)
- `docs/architecture.md` §2 (JWKS_URI wiring)
