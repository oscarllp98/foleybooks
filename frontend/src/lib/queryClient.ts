import { QueryClient } from '@tanstack/react-query'

// FE-11: the ONE TanStack Query client for all server state (AGENTS.md §2:
// every data fetch goes through it). Deliberately option-less: no spec, plan
// decision or ADR mandates a cache or retry policy, so library defaults rule
// (constitution §1.3 — simpler wins). Feature tests that need an instant
// failure state build their own retry-less client instead of importing this
// one, which would couple them to the production retry timing.
export const queryClient = new QueryClient()
