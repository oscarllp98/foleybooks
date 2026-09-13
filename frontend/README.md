# Foley Books — Frontend

React 19 + TypeScript (strict) SPA built with Vite. Talks only to the API
gateway (`http://localhost:8080/api/v1`).

## Stack

- TanStack Query v5 (server state), React Router v7 (routing)
- react-hook-form + zod (forms, schemas mirror backend validation)
- Tailwind CSS v4 (styling), Axios (HTTP, auth/refresh interceptors)
- Vitest + React Testing Library (tests), ESLint flat config + Prettier (lint/format)

## Commands

```powershell
npm install         # once
npm run dev         # dev server on http://localhost:5173
npm run lint        # ESLint (must exit 0)
npm test            # vitest run (must exit 0)
npm run build       # tsc -b && vite build (must exit 0)
```

## Layout

- `src/api/` — typed clients per backend service (the only place that performs HTTP)
- `src/components/` — shared presentational components
- `src/features/` — `auth/`, `catalog/`, `cart/`
- `src/hooks/`, `src/lib/` (axios + refresh interceptor), `src/routes/`, `src/types/`
