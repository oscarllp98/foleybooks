# Architecture — Foley Books

Skeleton delivered with P-07; keep in sync whenever a module, route, or integration
changes (diagrams updated alongside ADRs). Stack facts follow the repository's
engineering conventions; decisions are tracked in [`adr/`](adr/).

## 1. System context (C4 level 1)

Who uses the system and what it touches from the outside world.

```mermaid
flowchart LR
    visitor([Visitor<br/>shopper])
    customer([Registered customer])
    recruiter([Recruiter /<br/>portfolio viewer])

    subgraph foley["Foley Books"]
        app[Foley Books web app<br/>storefront + cart]
    end

    openlibrary[(Open Library<br/>Covers API)]
    mailpit[Mailpit dev inbox<br/>/ SMTP relay in prod]

    visitor -->|browse, search, register| app
    customer -->|login, manage cart| app
    recruiter -->|demo deploy, tests, docs| app
    app -.->|hotlink cover images by ISBN| openlibrary
    app -->|confirmation + notification emails| mailpit
```

- The only internet-facing component is the API gateway (C27); in local dev even
  that is host-only (`localhost:8080`).
- No payment provider in the MVP — checkout is explicitly out of scope (Spec 001).

## 2. Container view (C4 level 2)

Modules, ports, protocols, and the database-per-service boundary.

```mermaid
flowchart TB
    browser([Browser])

    subgraph fe["frontend (Vite dev :5173 / static hosting in prod)"]
        spa[React 19 + TypeScript SPA<br/>Tailwind, TanStack Query, React Router]
    end

    subgraph net["Docker compose network"]
        gw[api-gateway :8080<br/>Spring Cloud Gateway<br/>routing, CORS, JWT relay]
        eureka[discovery-service :8761<br/>Eureka registry — HTTP Basic, ADR-006]
        auth[auth-service :8081<br/>register / confirm / resend / login / refresh / logout<br/>RS256 JWT issuer + JWKS]
        catalog[catalog-service :8082<br/>books + categories read model<br/>search, availability]
        order[order-service :8083<br/>per-user persistent cart]
        authdb[(auth_db<br/>PostgreSQL 16)]
        catalogdb[(catalog_db<br/>PostgreSQL 16)]
        orderdb[(order_db<br/>PostgreSQL 16)]
        mail[Mailpit :1025 SMTP / :8025 UI]
    end

    browser --> spa
    spa -->|HTTP/JSON /api/v1/*<br/>Bearer access token relayed, not validated| gw

    gw -->|/api/v1/auth/**| auth
    gw -->|/api/v1/books/** /api/v1/categories/**| catalog
    gw -->|/api/v1/cart/**| order

    auth -->|JDBC + Flyway| authdb
    catalog -->|JDBC + Flyway| catalogdb
    order -->|JDBC + Flyway| orderdb

    order -->|OpenFeign east-west<br/>GET /books, /books/batch| catalog

    auth -->|confirmation emails| mail
    auth & catalog & order & gw -.->|register / discover| eureka
    catalog & order -.->|fetch public key| auth

    style eureka fill:#eee
    style mail fill:#eee
```

### Container responsibilities and contracts

| Container          | Exposes                              | Consumes                          | Owns data          |
| ------------------ | ------------------------------------ | --------------------------------- | ------------------ |
| `frontend`         | —                                    | gateway `/api/v1/**` (REST/JSON)  | none (cart in DB)  |
| `api-gateway`      | `:8080` (only public HTTP port)      | Eureka (routes), JWT relay        | none               |
| `discovery-service`| Eureka API/dashboard (Basic auth)    | —                                 | registry (memory)  |
| `auth-service`     | `/api/v1/auth/**`, `/oauth2/jwks`    | SMTP (Mailpit dev), Eureka        | `auth_db`          |
| `catalog-service`  | `/api/v1/books/**`, `/api/v1/categories/**` | Eureka, JWKS (auth)        | `catalog_db`       |
| `order-service`    | `/api/v1/cart/**`                    | catalog via OpenFeign, JWKS, Eureka | `order_db`       |

### Key constraints reflected above

- **Database-per-service**: no shared schemas, no cross-service FKs; cart stores
  `book_id` values only and enriches via Feign (C18, ADR-005).
- **Stateless services**: session state lives in JWTs / the DB — no `HttpSession`.
- **Security**: JWT RS256 issued by auth-service; every other service validates via
  `spring.security.oauth2.resourceserver.jwt` against the JWKS URI
  (`JWKS_URI` env). CORS only at the gateway. Deny-by-default elsewhere (C22, D-14).
- **Gateway-only east-west policy**: the frontend never talks to a domain service
  directly; inter-service calls (order → catalog) bypass the gateway via Eureka.

## 3. Component view (C4 level 3)

Per-service internals follow the package-by-feature layout mandated by this
repository (`api → service → repository`, entities never crossing the HTTP
boundary). Detailed component diagrams will be added per service in their specs
(auth, catalog, order) — this section is intentionally the layout, not invented
detail.

## 4. Cross-cutting flows

### Email confirmation (MVP, Spec 001)

```mermaid
sequenceDiagram
    participant U as User
    participant F as Frontend
    participant G as Gateway
    participant A as auth-service
    participant M as Mailpit (dev inbox)

    U->>F: register (email, password)
    F->>G: POST /api/v1/auth/register
    G->>A: proxy
    A->>A: create user UNVERIFIED, BCrypt-12,<br/>issue confirmation token (SHA-256 at rest)
    A--)M: async confirmation email (retried)
    A-->>F: 201 (identical for all duplicate branches)
    M-->>U: dev clicks link /verify-email?token=…
    U->>F: opens link
    F->>G: POST /api/v1/auth/confirm
    G->>A: proxy
    A->>A: atomic UPDATE status=VERIFIED
    A-->>F: 200 verified
```

### Browse + cart (MVP, Spec 001)

```mermaid
sequenceDiagram
    participant U as User
    participant F as Frontend
    participant G as Gateway
    participant C as catalog-service
    participant O as order-service

    U->>F: search/filter books
    F->>G: GET /api/v1/books?search=…&page=0&size=20
    G->>C: lb://catalog-service (public GET)
    C-->>F: page envelope (ILIKE title/author)

    U->>F: add to cart (needs login)
    F->>G: POST /api/v1/auth/login
    G->>F: TokenPair (RS256 access + opaque refresh)
    F->>G: POST /api/v1/cart/items (Bearer access token)
    G->>O: lb://order-service
    O->>C: Feign GET /books/{id} (east-west, stock check)
    O->>O: upsert cart line, capped by stock
    O-->>F: 201 CartResponse (server-computed totals)
```

## 5. Where to read next

- [`docs/adr/`](adr/) — decision records (mail sending: ADR-001; auth schema:
  ADR-002; Eureka security: ADR-006; public auth allowlist + security errors:
  ADR-007; more land with each spec phase)
- Module structure, endpoint map and technical decisions (D-01..D-15) are defined in
  the Spec 001 plan; product requirements live in the spec — both are maintained
  outside the public repository.
