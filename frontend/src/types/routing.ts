/**
 * FE-10's LC-27 return-path contract, hoisted out of routes/RequireAuth by
 * the FE-14 audit: a router-state shape consumed by the guard, the login
 * page AND feature components (FE-14's login prompt) must live in types/,
 * so every importer depends downward — no features → routes edge anywhere
 * in the tree (AGENTS.md §3 layout, §12.2).
 */
export interface LoginRedirectState {
  /** Pathname to return to after a successful login (LC-27's return path). */
  from: string
}
