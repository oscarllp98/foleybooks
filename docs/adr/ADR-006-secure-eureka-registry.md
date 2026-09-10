# ADR-006: Secure the Eureka registry with HTTP Basic

## Status

Accepted

## Context

The constitution (C22) requires deny-by-default authorization on every endpoint
except an explicit public allowlist (auth login/register, email confirmation/resend,
catalog GETs, health/info). Out of the box, the Eureka server exposes its dashboard,
the `/eureka/**` registry API, and all actuator endpoints without authentication.
C27 keeps the discovery service off the public internet, but the registry is still
reachable on the internal network and the earlier P-02 review flagged the open
dashboard and `/eureka/apps` API as a compliance gap. AGENTS.md §5 also requires
`metrics` to be exposed but restricted to `ROLE_ADMIN`.

The registry cannot use the JWT/JWKS resource-server setup used by the domain
services: Eureka clients register and renew with plain HTTP before any JWT exists,
and the Eureka server must not depend on the auth service for its own security.

## Decision

Secure the discovery-service with Spring Security HTTP Basic and a single in-memory
`ADMIN` user:

- Public (`permitAll`): `/actuator/health`, `/actuator/health/**`, `/actuator/info`
  and the servlet ERROR dispatch (so error pages render without an auth loop).
- `hasRole("ADMIN")`: the dashboard (`/`), the registry API (`/eureka/**`), and the
  rest of actuator (`/actuator/**`, including `metrics` per AGENTS.md §5).
- `anyRequest().denyAll()` keeps the deny-by-default posture (C22).
- CSRF protection ignores `/eureka/**` because Eureka clients use POST/PUT/DELETE
  to register, renew, and deregister; sessions are stateless.
- Credentials are read only from the environment (`EUREKA_SECURITY_USERNAME` /
  `EUREKA_SECURITY_PASSWORD`); there is no committed default and startup fails if
  they are missing (C19). The password is BCrypt-hashed (strength 12) at startup
  (AGENTS.md §5). Credentials are never logged (C24).
- Every service that registers with Eureka authenticates by embedding the
  credentials in its client configuration:
  `eureka.client.service-url.defaultZone=http://${EUREKA_USERNAME}:${EUREKA_PASSWORD}@discovery-service:8761/eureka/`.

## Consequences

- The registry dashboard and API now return `401` to anonymous callers.
- All Eureka clients (api-gateway, auth-service, catalog-service, order-service)
  must supply credentials; this ripples into their module configuration (P-03,
  AU-02, CA-02, OR-03) and into `docker-compose.yml` / `.env.example` (P-04).
- A new feature flag-free security surface exists in an infrastructure module,
  increasing the need for the `DiscoverySecurityIT` end-to-end check.
- Credentials rotate by changing the environment variables and restarting the
  discovery service and its clients together.
- If the registry later moves to a network-isolated segment where C27 can be
  proven, this decision can be revisited and the security config removed.
