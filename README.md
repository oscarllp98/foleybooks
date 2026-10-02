# Foley Books

A portfolio-grade e-commerce bookstore: browse and search real book data, manage a
persistent personal cart, with production-quality security, validation, and tests —
built as **Spring Boot 3.x microservices** with a **React + TypeScript** frontend.

![CI (main)](https://github.com/oscarllp98/foleybooks/actions/workflows/ci.yml/badge.svg)
![CI (develop)](https://github.com/oscarllp98/foleybooks/actions/workflows/ci.yml/badge.svg?branch=develop)

> **Status:** Spec 001 MVP is delivered — email-confirm auth (RS256 JWT, refresh-token
> rotation with reuse detection), catalog browse/search/filter, and a persistent
> per-user cart, all reachable through one gateway behind a single `docker compose up`.
> Every backend module carries ≥ 80% JaCoCo line coverage; integration tests run on
> Testcontainers PostgreSQL.

## Architecture

The React SPA talks to five backend modules through a single public entry point —
the API gateway; each domain service owns its own PostgreSQL database
(database-per-service, no shared schemas):

| Module            | Port | Database   | Responsibility                                        |
| ----------------- | ---- | ---------- | ----------------------------------------------------- |
| `frontend`        | 5173 | —          | React 19 + TS SPA; talks only to the gateway          |
| `api-gateway`     | 8080 | —          | Routing, CORS, JWT relay — no business logic          |
| `discovery-service` | 8761 | —        | Netflix Eureka registry (HTTP Basic secured)          |
| `auth-service`    | 8081 | auth_db    | Register, email confirm, login, refresh, JWKS (RS256) |
| `catalog-service` | 8082 | catalog_db | Books/categories read model, search, availability     |
| `order-service`   | 8083 | order_db   | Per-user persistent cart (Feign → catalog)            |

Diagrams (C4 context + container views) live in
[`docs/architecture.md`](docs/architecture.md); decisions are tracked in
[`docs/adr/`](docs/adr/).

## Tech stack

- **Backend**: Java 21, Spring Boot 3.5.x, Spring Cloud 2025.0.x, PostgreSQL 16,
  Flyway, Spring Security (JWT RS256 + JWKS), JPA, MapStruct, Bean Validation,
  OpenFeign, Testcontainers, JaCoCo (≥ 80% line coverage per module)
- **Frontend**: React 19, TypeScript 5 (strict), Vite, TanStack Query v5,
  React Router v7, react-hook-form + zod, Tailwind CSS, Vitest + RTL
- **Infra**: Docker Compose (full local stack), GitHub Actions CI

## Quickstart

Prerequisites: JDK 21, Node 20+, Docker Desktop running.

```powershell
# 1. Environment
Copy-Item .env.example .env      # then set the placeholder secrets

# 2. Full stack (gateway :8080, Mailpit UI :8025; registry, services and
#    databases stay network-internal per C27)
docker compose up -d --build

# 3. Smoke: the seeded catalog answers through the gateway
Invoke-RestMethod http://localhost:8080/api/v1/books?size=1 |
    ForEach-Object { $_.page.totalElements }                      # -> 12

# 4. Frontend dev server (the SPA calls the gateway at http://localhost:8080/api/v1)
cd frontend
Copy-Item .env.example .env.local   # VITE_API_BASE_URL, fine as-is for local dev
npm install
npm run dev                           # open http://localhost:5173
```

Verification gates:

```powershell
cd backend;  .\mvnw.cmd clean verify                 # compile + tests + coverage
cd frontend; npm run lint; npm test; npm run build   # three exit-0 gates
```

## Demo accounts

Portfolio-friendly demo credentials, seeded via Flyway and **already verified** — log in
with either one straight away (email confirmation is only required for self-registered
accounts):

| Role     | Email                     | Password         |
| -------- | ------------------------- | ---------------- |
| Admin    | `admin@foleybooks.com`    | `Admin@1234`     |
| Customer | `customer@foleybooks.com` | `Customer@1234`  |

New registrations send their confirmation link to [Mailpit](http://localhost:8025), a
disposable in-container inbox — no real email is ever sent in local dev.

## Book cover attribution

Cover images are hotlinked by ISBN from the
[Open Library Covers API](https://openlibrary.org/developers/api#Cover_api), a project
of the Internet Archive; artwork remains © the respective publishers. If a cover fails
to load, the UI falls back to a styled placeholder with descriptive alt text — broken
image icons never reach the user.

## Roadmap

- **Spec 001 — MVP (delivered)**: register/confirm/login/refresh/logout, catalog
  browse/search/filter, persistent per-user cart
- Later specs (orders & checkout, admin catalog management, payments) are intentionally
  out of the MVP slice.

## Documentation

- [`docs/architecture.md`](docs/architecture.md) — C4 context/container diagrams
  and key flows
- [`docs/adr/`](docs/adr/) — architecture decision records
