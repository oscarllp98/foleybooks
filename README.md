# Foley Books

A portfolio-grade e-commerce bookstore: browse and search real book data, manage a
persistent personal cart, with production-quality security, validation, and tests —
built as **Spring Boot 3.x microservices** with a **React + TypeScript** frontend.

![CI](https://github.com/oscarllp98/foleybooks/actions/workflows/ci.yml/badge.svg)

> **Status:** under construction — Phase 0 (platform bootstrap) is complete; the MVP
> (auth → catalog → cart) is being implemented per
> [`specs/001-foleybooks-mvp/`](specs/001-foleybooks-mvp/spec.md). Sections below
> describe the target product; features light up as their specs land.

## Architecture

Five modules behind a single public entry point (the API gateway); each domain
service owns its own PostgreSQL database (database-per-service, no shared schemas):

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

# 2. Full stack (gateway :8080, Mailpit UI :8025; everything else internal)
docker compose up -d --build

# 3. Or run a single service against the compose databases from the host
cd backend; .\mvnw.cmd spring-boot:run -pl auth-service

# 4. Frontend dev server (proxies to the gateway at http://localhost:8080/api/v1)
cd frontend; npm install; npm run dev
```

Verification gates:

```powershell
cd backend;  .\mvnw.cmd clean verify                 # compile + tests + coverage
cd frontend; npm run lint; npm test; npm run build   # three exit-0 gates
```

## Demo accounts

Portfolio-friendly demo credentials (seeded via Flyway once the auth service ships):

| Role    | Email                     | Password       |
| ------- | ------------------------- | -------------- |
| Admin   | `admin@foleybooks.com`    | `Admin@1234`   |
| Customer| `customer@foleybooks.com` | `Customer@1234`|

Email confirmation during local dev lands in [Mailpit](http://localhost:8025), a
disposable in-container inbox — no real email is ever sent.

Book cover images are hotlinked from the
[Open Library Covers API](https://openlibrary.org/developers/api#Cover_api) by ISBN,
with an offline placeholder fallback.

## Roadmap

- [Spec 001 — MVP](specs/001-foleybooks-mvp/spec.md): register/confirm/login, catalog
  browse/search/filter, persistent cart
- Later specs (orders, admin catalog management, payments) are intentionally out of
  the MVP slice.

## Documentation map

[`AGENTS.md`](AGENTS.md) (engineering conventions) →
[`docs/constitution.md`](docs/constitution.md) (non-negotiable principles) →
[`specs/`](specs/) (product truth) → [`docs/architecture.md`](docs/architecture.md)
(diagrams) → [`docs/adr/`](docs/adr/) (decision history).
