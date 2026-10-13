import type { UserSummary } from '../types/auth'

export interface AuthTokens {
  accessToken: string
  refreshToken: string
}

/**
 * What a session-carrying response looks like: the token pair plus the
 * identity that owns it. `TokenPair` from the API satisfies this shape.
 */
export interface SessionTokens extends AuthTokens {
  user?: UserSummary
}

/** Immutable view of the store — safe as a `useSyncExternalStore` snapshot. */
export interface TokenSnapshot {
  accessToken: string | null
  refreshToken: string | null
  user: UserSummary | null
  sessionExpired: boolean
}

const REFRESH_TOKEN_KEY = 'foleybooks.refreshToken'
const SESSION_USER_KEY = 'foleybooks.sessionUser'

// D-13: the access token lives in memory only; the refresh credential is
// persisted so the session survives reloads within its 7-day window (FR-04).
let accessToken: string | null = null

// ADR-011 (FE-04 amendment): the user identity returned by login/refresh is
// persisted next to the refresh token so a reload can show the signed-in
// state WITHOUT rotating the single-use credential at startup — a boot-time
// refresh would race other tabs into FR-04's reuse-revoke trap (LC-08).
let sessionExpired = false

const listeners = new Set<() => void>()

// FE-04 (ADR-011): this module is THE session store. AuthContext wraps it
// instead of keeping a parallel copy, and React observes rotations performed
// by the 401 interceptor (LC-07/LC-22) through subscribeTokenChanges — the
// store itself knows nothing about React.
let snapshot: TokenSnapshot = readSnapshot()

function readSnapshot(): TokenSnapshot {
  return {
    accessToken,
    refreshToken: localStorage.getItem(REFRESH_TOKEN_KEY),
    user: readStoredUser(),
    sessionExpired,
  }
}

function readStoredUser(): UserSummary | null {
  const raw = localStorage.getItem(SESSION_USER_KEY)
  if (raw === null) return null
  try {
    const parsed: unknown = JSON.parse(raw)
    return isUserSummary(parsed) ? parsed : null
  } catch {
    return null
  }
}

function isUserSummary(value: unknown): value is UserSummary {
  if (typeof value !== 'object' || value === null) return false
  const candidate: Partial<UserSummary> = value as Partial<UserSummary>
  return (
    typeof candidate.id === 'string' &&
    typeof candidate.email === 'string' &&
    (candidate.role === 'CUSTOMER' || candidate.role === 'ADMIN')
  )
}

function notify(): void {
  snapshot = readSnapshot()
  for (const listener of listeners) listener()
}

export function subscribeTokenChanges(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function getTokenSnapshot(): TokenSnapshot {
  return snapshot
}

export function getAccessToken(): string | null {
  return accessToken
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY)
}

export function setTokens(tokens: SessionTokens): void {
  accessToken = tokens.accessToken
  localStorage.setItem(REFRESH_TOKEN_KEY, tokens.refreshToken)
  if (tokens.user) {
    localStorage.setItem(SESSION_USER_KEY, JSON.stringify(tokens.user))
  }
  sessionExpired = false
  notify()
}

export function clearTokens(): void {
  accessToken = null
  localStorage.removeItem(REFRESH_TOKEN_KEY)
  localStorage.removeItem(SESSION_USER_KEY)
  notify()
}

/**
 * LC-07: the refresh credential is gone — end the session and flag why, so
 * the login page can tell "expired" apart from "never signed in" (FE-10).
 */
export function markSessionExpired(): void {
  accessToken = null
  localStorage.removeItem(REFRESH_TOKEN_KEY)
  localStorage.removeItem(SESSION_USER_KEY)
  sessionExpired = true
  notify()
}
