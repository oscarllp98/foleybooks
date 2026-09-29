import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { useAuth } from '../hooks/useAuth'

/** Router state LoginPage consumes to send the visitor back where they came from. */
export interface LoginRedirectState {
  /** Pathname to return to after a successful login (LC-27's return path). */
  from: string
}

/**
 * FE-10 (FR-10, LC-27): guards a route element for signed-in users only.
 * An anonymous visitor who opens a guarded URL directly is redirected to
 * /login carrying the pathname, and LoginPage replays it after
 * authentication so "after logging in, the cart is available" holds. The
 * auth decision itself is the session store's (ADR-012) — this component
 * reads it and nothing else; server-side it is still the resource server
 * that denies (C26, C22), never the guard.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { isAuthenticated } = useAuth()
  const location = useLocation()

  if (!isAuthenticated) {
    return (
      <Navigate
        to="/login"
        state={{ from: location.pathname } satisfies LoginRedirectState}
        replace
      />
    )
  }
  return <>{children}</>
}
